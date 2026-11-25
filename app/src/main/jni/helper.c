#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <errno.h>
#include <stdint.h>
#include <ctype.h>
#include <sys/ptrace.h>
#include <sys/wait.h>

#define TARGET_PKG "com.dts.freefiremax"
#define MAX_BYTE 32
#define MAX_STR 16
#define MAX_B 64

typedef struct { uint64_t rva; int n; unsigned char orig[MAX_B]; unsigned char patch[MAX_B]; } bpatch_t;
typedef struct { char find[64]; char repl[64]; } spatch_t;

static bpatch_t g_b[MAX_BYTE]; static int g_bc = 0;
static spatch_t g_s[MAX_STR];  static int g_sc = 0;

static int hex2bin(const char *s, unsigned char *out, int max) {
    int n = 0;
    while (s[0] && s[1] && n < max) {
        int hi = isdigit((unsigned char)s[0]) ? s[0]-'0' : tolower((unsigned char)s[0])-'a'+10;
        int lo = isdigit((unsigned char)s[1]) ? s[1]-'0' : tolower((unsigned char)s[1])-'a'+10;
        if (hi<0||hi>15||lo<0||lo>15) return -1;
        out[n++] = (unsigned char)((hi<<4)|lo); s += 2;
    }
    return n;
}

static void read_input(void) {
    char line[512];
    while (fgets(line, sizeof line, stdin)) {
        char *nl = strchr(line,'\n'); if (nl) *nl = 0;
        if (!line[0]) continue;
        if (line[0]=='B' && line[1]=='|') {
            char *a = line+2, *p1 = strchr(a,'|'); if (!p1) continue; *p1++ = 0;
            char *p2 = strchr(p1,'|'); if (!p2) continue; *p2++ = 0;
            if (g_bc >= MAX_BYTE) continue;
            bpatch_t *b = &g_b[g_bc];
            b->rva = strtoull(a, NULL, 16);
            int x = hex2bin(p1, b->orig, MAX_B), y = hex2bin(p2, b->patch, MAX_B);
            if (x <= 0 || x != y) continue;
            b->n = x; g_bc++;
        } else if (line[0]=='S' && line[1]=='|') {
            char *a = line+2, *p1 = strchr(a,'|'); if (!p1) continue; *p1++ = 0;
            if (g_sc >= MAX_STR) continue;
            snprintf(g_s[g_sc].find, sizeof g_s[g_sc].find, "%s", a);
            snprintf(g_s[g_sc].repl, sizeof g_s[g_sc].repl, "%s", p1);
            g_sc++;
        }
    }
}

static pid_t find_pid(void) {
    DIR *d = opendir("/proc"); if (!d) return -1;
    struct dirent *e; pid_t r = -1;
    while ((e = readdir(d))) {
        if (!isdigit((unsigned char)e->d_name[0])) continue;
        char p[64]; snprintf(p, sizeof p, "/proc/%s/cmdline", e->d_name);
        int fd = open(p, O_RDONLY); if (fd < 0) continue;
        char b[256]; ssize_t k = read(fd, b, sizeof b - 1); close(fd);
        if (k <= 0) continue;
        b[k] = 0;
        if (!strcmp(b, TARGET_PKG)) { r = atoi(e->d_name); break; }
    }
    closedir(d); return r;
}

static uint64_t find_base(pid_t pid, const char *lib) {
    char p[64]; snprintf(p, sizeof p, "/proc/%d/maps", pid);
    FILE *f = fopen(p, "r"); if (!f) return 0;
    char l[512]; uint64_t base = 0;
    while (fgets(l, sizeof l, f)) {
        if (!strstr(l, lib)) continue;
        uint64_t st = 0, off = 0; char pm[8] = {0};
        if (sscanf(l, "%lx-%*lx %7s %lx", &st, pm, &off) != 3) continue;
        if (off == 0 && pm[0] == 'r') { base = st; break; }
    }
    fclose(f); return base;
}

static unsigned char *find_pat(unsigned char *h, size_t hn, const char *n, size_t nn) {
    if (nn > hn) return NULL;
    for (size_t i = 0; i + nn <= hn; i++) if (!memcmp(h + i, n, nn)) return h + i;
    return NULL;
}

static void strpatch(int fd, pid_t pid, const char *lib, const char *find, const char *repl, int restore, int *hits) {
    const char *f = restore ? repl : find;
    const char *r = restore ? find : repl;
    int fl = strlen(f), rl = strlen(r);
    if (rl > fl) return;

    char p[64]; snprintf(p, sizeof p, "/proc/%d/maps", pid);
    FILE *m = fopen(p, "r"); if (!m) return;
    char l[512];
    const size_t CH = 1 << 20;
    while (fgets(l, sizeof l, m)) {
        if (!strstr(l, lib)) continue;
        uint64_t st = 0, en = 0; char pm[8] = {0};
        if (sscanf(l, "%lx-%lx %7s", &st, &en, pm) != 3) continue;
        if (pm[0] != 'r' || pm[1] == 'x') continue;

        unsigned char *buf = malloc(CH + 256);
        if (!buf) continue;
        size_t carry = 0; uint64_t pos = st;
        while (pos < en) {
            size_t want = (en - pos > CH) ? CH : (size_t)(en - pos);
            ssize_t got = pread(fd, buf + carry, want, (off_t)pos);
            if (got <= 0) break;
            size_t total = carry + got;
            unsigned char *sp = buf; size_t rem = total;
            while (1) {
                unsigned char *hit = find_pat(sp, rem, f, fl);
                if (!hit) break;
                uint64_t ad = pos - carry + (uint64_t)(hit - buf);
                int ok = 1; unsigned char nb = 0;
                if (pread(fd, &nb, 1, (off_t)(ad + fl)) == 1 && nb != 0) ok = 0;
                if (ok) {
                    unsigned char out[128];
                    memcpy(out, r, rl); memset(out + rl, 0, fl - rl);
                    if (pwrite(fd, out, fl, (off_t)ad) == fl) (*hits)++;
                }
                size_t adv = (size_t)(hit - sp) + fl;
                sp += adv; rem -= adv;
            }
            carry = (total >= (size_t)(fl - 1)) ? (size_t)(fl - 1) : total;
            memmove(buf, buf + total - carry, carry);
            pos += got;
        }
        free(buf);
    }
    fclose(m);
}

int main(int argc, char **argv) {
    const char *action = "apply", *lib = "libil2cpp.so";
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i],"--action") && i+1<argc) action = argv[++i];
        else if (!strcmp(argv[i],"--lib") && i+1<argc) lib = argv[++i];
    }
    int probe = !strcmp(action,"probe"), restore = !strcmp(action,"restore");
    if (!probe) read_input();

    pid_t pid = find_pid();
    if (pid < 0) { printf("{\"ok\":false,\"err\":\"game_not_running\"}\n"); return 1; }

    uint64_t base = find_base(pid, lib);
    if (!base) { printf("{\"ok\":false,\"err\":\"lib_not_found\",\"pid\":%d}\n", pid); return 1; }
    if (probe) { printf("{\"ok\":true,\"pid\":%d,\"base\":\"0x%llx\"}\n", pid, (unsigned long long)base); return 0; }

    if (ptrace(PTRACE_ATTACH, pid, 0, 0)) { printf("{\"ok\":false,\"err\":\"ptrace\",\"code\":%d}\n", errno); return 1; }
    int stt; waitpid(pid, &stt, 0);

    char mem[64]; snprintf(mem, sizeof mem, "/proc/%d/mem", pid);
    int fd = open(mem, O_RDWR);
    if (fd < 0) { printf("{\"ok\":false,\"err\":\"mem\",\"code\":%d}\n", errno); ptrace(PTRACE_DETACH,pid,0,0); return 1; }

    int fails = 0;
    printf("{\"ok\":true,\"pid\":%d,\"base\":\"0x%llx\",\"b\":[", pid, (unsigned long long)base);
    for (int i = 0; i < g_bc; i++) {
        bpatch_t *p = &g_b[i];
        uint64_t ad = base + p->rva;
        unsigned char cur[MAX_B]; const char *s = "ok";
        if (pread(fd, cur, p->n, (off_t)ad) != p->n) s = "read_fail";
        else if (memcmp(cur, p->orig, p->n)) s = memcmp(cur, p->patch, p->n) ? "mismatch" : "already";
        if (!strcmp(s,"ok") && pwrite(fd, restore ? p->orig : p->patch, p->n, (off_t)ad) != p->n) s = "write_fail";
        if (strcmp(s,"ok") && strcmp(s,"already")) fails++;
        printf("%s{\"rva\":\"0x%llx\",\"s\":\"%s\"}", i?",":"", (unsigned long long)p->rva, s);
    }
    printf("],\"s\":[");
    for (int i = 0; i < g_sc; i++) {
        int hits = 0;
        strpatch(fd, pid, lib, g_s[i].find, g_s[i].repl, restore, &hits);
        if (!hits) fails++;
        printf("%s{\"find\":\"%s\",\"hits\":%d}", i?",":"", g_s[i].find, hits);
    }
    printf("],\"fails\":%d}\n", fails);

    close(fd);
    ptrace(PTRACE_DETACH, pid, 0, 0);
    return fails ? 2 : 0;
}

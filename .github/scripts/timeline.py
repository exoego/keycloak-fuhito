import datetime, glob, re
rows = []
for f in glob.glob('build/test-results/integrationTest/*.xml'):
    s = open(f).read()
    m = re.search(r'<testsuite name="([^"]+)" tests="(\d+)".*?timestamp="([^"]+)".*?time="([\d.]+)"', s)
    if m:
        t = datetime.datetime.fromisoformat(m.group(3).replace('Z', '+00:00'))
        rows.append((t, float(m.group(4)), m.group(1).split('.')[-1]))
        for line in re.findall(r'(KCLOG [^\n]*|Container quay[^\n]*started in [^\n]*|pull took [^\n]*|Container postgres[^\n]*started in [^\n]*)', s):
            print('   ', m.group(1).split('.')[-1], line[:200])
rows.sort()
t0 = rows[0][0]
end = max(r[0] + datetime.timedelta(seconds=r[1]) for r in rows)
print('span %.1fs, sum %.1fs' % ((end - t0).total_seconds(), sum(r[1] for r in rows)))
for t, d, n in rows:
    print('start +%6.1fs  %6.1fs  %s' % ((t - t0).total_seconds(), d, n))

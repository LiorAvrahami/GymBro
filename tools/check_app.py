"""Checks what the Kotlin compiler cannot see without the Android SDK (there is none in the cloud session that maintains
this project; GitHub compiles): every R.<type>.<name> the code uses exists in the resources, every @drawable/@color/
@style/@id the layouts use exists, every layout's class names are real, and every activity is in the manifest.
Run: python tools/check_app.py"""
import os, re, sys, glob

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
RES = os.path.join(ROOT, 'app/src/main/res')
SRC = os.path.join(ROOT, 'app/src/main/java')
problems = []

have = {'drawable': set(), 'layout': set(), 'color': set(), 'style': set(), 'id': set(), 'string': set(), 'mipmap': set(), 'xml': set()}
for d in os.listdir(RES):
    kind = d.split('-')[0]
    for f in os.listdir(os.path.join(RES, d)):
        name = f.rsplit('.', 1)[0]
        if kind in ('drawable', 'layout', 'mipmap', 'xml'):
            have[kind].add(name)
        text = open(os.path.join(RES, d, f), encoding='utf-8').read()
        have['id'].update(re.findall(r'@\+id/(\w+)', text))
        for t, n in re.findall(r'<(color|style|string) name="([\w.]+)"', text):
            have[t].add(n.replace('.', '_'))

for f in glob.glob(SRC + '/**/*.kt', recursive=True):
    text = open(f, encoding='utf-8').read()
    for t, n in re.findall(r'\bR\.(drawable|layout|color|style|id|string|mipmap|xml)\.(\w+)', text):
        if n not in have[t]:
            problems.append('%s: R.%s.%s does not exist' % (os.path.basename(f), t, n))

classes = {os.path.basename(f)[:-3] for f in glob.glob(SRC + '/**/*.kt', recursive=True)}
for f in glob.glob(RES + '/**/*.xml', recursive=True):
    text = open(f, encoding='utf-8').read()
    for t, n in re.findall(r'@(drawable|color|style|mipmap|xml)/([\w.]+)', text):
        if n.replace('.', '_') not in have[t]:
            problems.append('%s: @%s/%s does not exist' % (os.path.relpath(f, RES), t, n))
    for c in re.findall(r'<org\.lior\.gymbro\.(\w+)', text):
        src = ' '.join(open(g, encoding='utf-8').read() for g in glob.glob(SRC + '/**/*.kt', recursive=True))
        if not re.search(r'class %s\b' % c, src):
            problems.append('%s: class %s does not exist' % (os.path.relpath(f, RES), c))

manifest = open(os.path.join(ROOT, 'app/src/main/AndroidManifest.xml'), encoding='utf-8').read()
for f in glob.glob(SRC + '/**/*Activity.kt', recursive=True):
    name = os.path.basename(f)[:-3]
    if '.%s"' % name not in manifest:
        problems.append('%s is not in the manifest' % name)

for p in problems:
    print(p)
print('%d problem(s)' % len(problems))
sys.exit(1 if problems else 0)

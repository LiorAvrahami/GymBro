"""Reads a GymBro export (the statistics page's "Export all data": GymBro_export_<time>.json) and prints what matters
for diagnostics: the app and the phone, where the data is kept and whether that works, the stations and their QR
codes, the sets, the photos, and the log (counts by level and tag, every warning and error, the last lines).

    python tools/read_export.py GymBro_export_2026-11-24_221000.json            # the report
    python tools/read_export.py EXPORT.json --images out/                      # also writes the photos to out/
    python tools/read_export.py EXPORT.json --log 300                          # the last 300 log lines
    python tools/read_export.py EXPORT.json --grep scan                        # log lines whose tag or text has "scan"
"""
import argparse
import base64
import collections
import datetime
import json
import os
import sys


def ts(ms):
    if not ms:
        return '-'
    return datetime.datetime.fromtimestamp(ms / 1000).strftime('%Y-%m-%d %H:%M:%S')


def line(e):
    d = e.get('d')
    extra = (' ' + json.dumps(d, ensure_ascii=False)) if d else ''
    return '%s %s %-8s %s%s' % (e.get('ts') or ts(e.get('t')), e.get('lvl', '?'), e.get('tag', '?'), e.get('msg', ''), extra)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('export')
    ap.add_argument('--images', help='write the photos to this folder')
    ap.add_argument('--log', type=int, default=60, help='how many of the last log lines to print')
    ap.add_argument('--grep', help='print the log lines whose tag or message contains this')
    a = ap.parse_args()
    with open(a.export, encoding='utf-8') as f:
        x = json.load(f)

    print('== export', x.get('format'), 'v%s' % x.get('formatVersion'), 'made', x.get('exportedAtLocal'))
    app = x.get('app', {})
    print('== app', app.get('versionName'), '(code %s)' % app.get('versionCode'), 'installed', app.get('firstInstallLocal'),
          'updated', app.get('lastUpdateLocal'))
    dev = x.get('device', {})
    print('== phone', dev.get('manufacturer'), dev.get('model'), 'Android', dev.get('android'), '(sdk %s)' % dev.get('sdk'),
          dev.get('screen'), dev.get('locale'), dev.get('timezone'), 'camera permission:', dev.get('cameraPermission'))
    st = x.get('storage', {})
    print('== storage: folder', st.get('folder'), '| reachable:', st.get('folderReachable'), '| last folder write ok:',
          st.get('lastFolderWriteOk'), ts(st.get('lastFolderWriteAt')))
    if st.get('lastFolderError'):
        print('   last folder error:', st.get('lastFolderError'))
    print('   load:', st.get('loadReport'))
    print('   app files: %d, folder files: %d' % (len(st.get('appFiles', [])), len(st.get('folderFiles', []))))
    print('   folder permissions:', dev.get('persistedFolderPermissions'))

    s = x.get('summary', {})
    print('== summary: %s stations (%s with QR, %s deleted), %s sets, %s reps, %.0f kg, %s training days, streak %s'
          % (s.get('stations'), s.get('stationsWithQr'), s.get('deletedStations'), s.get('sets'), s.get('reps'),
             s.get('volumeKg') or 0, s.get('trainingDays'), s.get('streakDays')))
    data = x.get('data', {})
    print('   data saved', ts(data.get('savedAt')), '(save #%s), created %s' % (data.get('saveCount'), ts(data.get('createdAt'))))

    print('== stations')
    per = {p['id']: p for p in s.get('perStation', [])}
    for st_ in data.get('stations', []):
        p = per.get(st_['id'], {})
        print('   %-28s %s inc=%s images=%d sets=%s best=%s QR=%s%s' % (
            st_.get('name'), '(deleted)' if st_.get('deleted') else '         ', st_.get('weightIncrement'),
            len(st_.get('images', [])), p.get('sets', 0), p.get('bestWeight', 0), st_.get('qrValue'),
            ' [%s]' % st_.get('qrFormat') if st_.get('qrFormat') else ''))

    uses = data.get('uses', [])
    print('== sets: %d (the last 15)' % len(uses))
    names = {st_['id']: st_.get('name') for st_ in data.get('stations', [])}
    for u in uses[-15:]:
        print('   %s  %-24s %2s x %-6s diff %-2s via %-4s on page %ss' % (
            ts(u.get('time')), names.get(u.get('stationId'), u.get('stationName')), u.get('reps'), u.get('weight'),
            u.get('difficulty'), u.get('source'), ((u.get('time') or 0) - (u.get('openedAt') or 0)) // 1000 if u.get('openedAt') else '?'))

    imgs = x.get('images', [])
    print('== photos: %d (%d missing), %.1f MB' % (len(imgs), sum(1 for i in imgs if i.get('missing')),
                                                    sum(i.get('bytes', 0) for i in imgs) / 1048576))
    if a.images:
        os.makedirs(a.images, exist_ok=True)
        for i in imgs:
            if i.get('base64'):
                with open(os.path.join(a.images, i['file']), 'wb') as f:
                    f.write(base64.b64decode(i['base64']))
        print('   written to', a.images)

    log = x.get('log', [])
    print('== log: %d lines, %s .. %s, sessions: %d' % (len(log), log[0].get('ts') if log else '-', log[-1].get('ts') if log else '-',
                                                      len({e.get('s') for e in log})))
    print('   by level:', dict(collections.Counter(e.get('lvl') for e in log)))
    print('   by tag:', dict(collections.Counter(e.get('tag') for e in log).most_common()))
    bad = [e for e in log if e.get('lvl') in ('W', 'E')]
    print('== warnings and errors: %d' % len(bad))
    for e in bad:
        print('   ' + line(e))
        if e.get('err'):
            print('      ' + '\n      '.join(e['err'].strip().splitlines()[:12]))
    if a.grep:
        g = a.grep.lower()
        hits = [e for e in log if g in (e.get('tag', '') + ' ' + e.get('msg', '') + ' ' + json.dumps(e.get('d', ''))).lower()]
        print('== log lines with "%s": %d' % (a.grep, len(hits)))
        for e in hits:
            print('   ' + line(e))
    print('== the last %d log lines' % min(a.log, len(log)))
    for e in log[-a.log:]:
        print('   ' + line(e))


if __name__ == '__main__':
    sys.exit(main())

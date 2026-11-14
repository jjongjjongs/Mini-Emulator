#!/usr/bin/env python3
"""Builds the in-app help (설정 → 도움말) from the overview document.

docs/overview/overview.tpl.html is the document, with {{VERSION}} (versionName from
app/build.gradle) and {{DATE}} (today, or the DOC_DATE environment variable); its pictures
are docs/overview/img/*.jpg. The result goes to app/src/main/assets/help/ (overview.html and
img/; overview-light.html and overview-dark.html fix the theme), which the APK carries, so every release shows the document of that version.
Run from anywhere: python3 docs/overview/render.py
"""
import datetime
import os
import re
import shutil
import sys

here = os.path.dirname(os.path.abspath(__file__))
repo = os.path.dirname(os.path.dirname(here))
gradle = open(os.path.join(repo, 'app', 'build.gradle'), encoding='utf-8').read()
version = re.search(r"versionName\s+'([^']+)'", gradle).group(1)
date = os.environ.get('DOC_DATE') or datetime.date.today().isoformat()
html = open(os.path.join(here, 'overview.tpl.html'), encoding='utf-8').read()
html = html.replace('{{VERSION}}', version).replace('{{DATE}}', date)
left = re.findall(r'\{\{[^}]*\}\}', html)
if left:
    sys.exit('unfilled placeholders: %s' % sorted(set(left)))
for name in re.findall(r'src="img/([^"]+)"', html):
    if not os.path.isfile(os.path.join(here, 'img', name)):
        sys.exit('missing picture: img/%s' % name)
out = os.path.join(repo, 'app', 'src', 'main', 'assets', 'help')
if os.path.isdir(out):
    shutil.rmtree(out)
shutil.copytree(os.path.join(here, 'img'), os.path.join(out, 'img'))
with open(os.path.join(out, 'overview.html'), 'w', encoding='utf-8') as f:
    f.write(html)
# The app's 화면 테마 picks one of these (its WebView does not follow the app's own choice).
for theme in ('light', 'dark'):
    with open(os.path.join(out, 'overview-%s.html' % theme), 'w', encoding='utf-8') as f:
        f.write(html.replace('<html lang="ko">', '<html lang="ko" data-theme="%s">' % theme, 1))
print('help: version %s, %s, %d pictures' % (version, date, len(os.listdir(os.path.join(out, 'img')))))

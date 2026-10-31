#!/usr/bin/env python3
"""Test of MIC_JS / MIC_STOP_JS from WebAppActivity.kt in desktop Chrome with a fake microphone (Playwright).
Checks: tracks live before, ended after stop (also MediaStreamTrack.clone and MediaStream.clone), the page gets an
"ended" event, a running MediaRecorder stops, a second stream is stopped too, double injection is harmless.
Usage: python tools/mic_js_test.py [path to chrome]"""
import os
import re
import sys
import tempfile

from playwright.sync_api import sync_playwright

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = open(os.path.join(ROOT, "app/src/main/java/de/camperflower/homehyrax/WebAppActivity.kt"), encoding="utf-8").read()
STR = re.compile(r'"((?:[^"\\]|\\.)*)"')


def const(name):
    block = SRC[SRC.index(f"private const val {name} ="):]
    block = block[:block.index("\n\n")]
    return "".join(m.encode().decode("unicode_escape") for m in STR.findall(block))


MIC, STOP = const("MIC_JS"), const("MIC_STOP_JS")
PAGE = f"""<html><body><pre id=o>wait</pre><script>{MIC};{MIC}</script><script>
(async()=>{{var r=[];try{{
var m=await navigator.mediaDevices.getUserMedia({{audio:true}});var t=m.getAudioTracks()[0];
var c=t.clone();var mc=m.clone().getAudioTracks()[0];
var ev=0;t.onended=function(){{ev++;}};var rec=new MediaRecorder(m);var rs=0;rec.onstop=function(){{rs=1;}};rec.start();
r.push(t.readyState+','+c.readyState+','+mc.readyState);{STOP};await new Promise(z=>setTimeout(z,300));
r.push(t.readyState+','+c.readyState+','+mc.readyState+','+ev+','+rs);
var m2=await navigator.mediaDevices.getUserMedia({{audio:true}});{STOP};r.push(m2.getAudioTracks()[0].readyState);
}}catch(e){{r.push('err '+e)}}document.getElementById('o').textContent=r.join(' ');}})();
</script></body></html>"""
EXPECTED = "live,live,live ended,ended,ended,1,1 ended"

chrome = sys.argv[1] if len(sys.argv) > 1 else r"C:\Program Files\Google\Chrome\Application\chrome.exe"
with tempfile.TemporaryDirectory() as d:
    page_file = os.path.join(d, "mic.html")
    open(page_file, "w", encoding="utf-8").write(PAGE)
    with sync_playwright() as p:
        b = p.chromium.launch(executable_path=chrome if os.path.exists(chrome) else None,
                              args=["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream"])
        pg = b.new_page()
        pg.goto("file:///" + page_file.replace("\\", "/"))
        pg.wait_for_function("document.getElementById('o').textContent!=='wait'", timeout=10000)
        got = pg.text_content("#o")
        b.close()
print(got)
if got != EXPECTED:
    sys.exit(f"FAILED, expected: {EXPECTED}")
print("OK")

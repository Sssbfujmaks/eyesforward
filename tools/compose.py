"""시연 영상 만들기: 오프닝 카드 + [차량 화면(왼쪽) + 태블릿 화면(오른쪽) + 마이크 소리 + 장면 자막] + 엔딩 카드.

사용법: python compose.py <recordings/시각 폴더> [출력.mp4]
자막은 중계 서버가 남긴 server/events.jsonl(태블릿 명령·차량 이벤트와 시각)에서 자동으로 만든다.
필요: ffmpeg, Pillow
"""
import json
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
FONT = "/System/Library/Fonts/AppleSDGothicNeo.ttc"
W, H = 1920, 1080
BG = (14, 17, 22)
LANE = (242, 179, 0)


def font(size, bold=True):
    try:
        return ImageFont.truetype(FONT, size, index=6 if bold else 0)
    except Exception:
        return ImageFont.truetype(FONT, size)


def text_png(path, text, size, color=(232, 236, 242), bold=True):
    f = font(size, bold)
    l, t, r, b = f.getbbox(text)
    img = Image.new("RGBA", (r - l, b - t), (0, 0, 0, 0))
    ImageDraw.Draw(img).text((-l, -t), text, font=f, fill=color)
    img.save(path)


def header_png(path, num, title, result):
    """상단: 장면 번호(노란 원) + 상황 제목 + 무엇을 보여 주는지 한 줄."""
    ft, fr, fn = font(40), font(24, False), font(30)
    img = Image.new("RGBA", (1500, 104), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse([0, 2, 52, 54], fill=LANE)
    n = str(num)
    l, t, r, b = fn.getbbox(n)
    d.text((26 - (r - l) / 2 - l, 28 - (b - t) / 2 - t), n, font=fn, fill=(20, 24, 29))
    l, t, r, b = ft.getbbox(title)
    d.text((70 - l, 28 - (b - t) / 2 - t), title, font=ft, fill=(240, 242, 245))
    d.text((70, 68), result, font=fr, fill=(168, 178, 192))
    img.save(path)


TAGS = {"클릭": ((84, 110, 150), (235, 240, 248)), "직접": ((84, 110, 150), (235, 240, 248)),
        "엄마": ((46, 160, 110), (240, 250, 245)), "차": (LANE, (20, 24, 29))}


def sub_png(path, tag, text):
    """하단 자막: [태그] + 내용. 태그는 클릭/직접(패널·손 동작), 엄마(부모 음성), 차(차량 음성 안내)."""
    fb, ft = font(34), font(26)
    bg, fg = TAGS[tag]
    tl, tt, tr, tb = ft.getbbox(tag)
    l, t, r, b = fb.getbbox(text)
    tw, w, h = tr - tl + 28, r - l, 64
    W2 = tw + 18 + w + 40
    img = Image.new("RGBA", (W2, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, W2 - 1, h - 1], radius=16, fill=(10, 12, 16, 225))
    d.rounded_rectangle([12, 12, 12 + tw, h - 13], radius=10, fill=bg)
    d.text((12 + 14 - tl, h / 2 - (tb - tt) / 2 - tt), tag, font=ft, fill=fg)
    d.text((12 + tw + 18 - l, h / 2 - (b - t) / 2 - t), text, font=fb, fill=(245, 246, 248))
    img.save(path)
    return W2, h


def watermark_png(path):
    img = Image.new("RGBA", (260, 70), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    f1, f2 = font(36), font(18, False)
    d.ellipse([0, 14, 18, 32], fill=LANE)
    d.text((28, 2), "앞만봐", font=f1, fill=(240, 242, 245, 200))
    d.text((30, 46), "Eyes Forward", font=f2, fill=(168, 178, 192, 200))
    img.save(path)


def card_png(path, small, big, foot=""):
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    d.text((140, 330), small, font=font(40, False), fill=(154, 161, 169))
    y = 410
    for line in big.split("\n"):
        d.text((140, y), line, font=font(88), fill=(236, 237, 234))
        y += 120
    if foot:
        d.text((140, y + 40), foot, font=font(30, False), fill=(154, 161, 169))
    for x in range(0, W, 112):  # 차선 점선
        d.rectangle([x, 900, x + 64, 906], fill=LANE)
    img.save(path)


SPEECH = {
    "battery_low": "첫째 태블릿이 도착 전에 꺼질 수 있어요. 충전해 주세요.",
    "battery_charging": "첫째 태블릿 충전을 시작했어요. 도착까지 충분해요.",
    "voice_next": "첫째, 다음 편을 틀었어요.",
    "lock_drive": "첫째가 벨트를 풀었어요. 다시 매면 이어서 나와요.",
    "voice_volume_down": "{who} 소리를 줄였어요.",
    "voice_remaining": "목적지까지 1시간 52분 남았어요. 마지막 편까지 충분해요.",
}
PANEL = {"belt left true": "첫째 벨트 매기", "belt left false": "첫째 벨트 풀기",
         "door left true": "첫째 뒷문 열기", "door left false": "첫째 뒷문 닫기",
         "belt right true": "둘째 벨트 매기", "belt right false": "둘째 벨트 풀기",
         "door right true": "둘째 뒷문 열기", "door right false": "둘째 뒷문 닫기",
         "gear D": "기어 D (주행)", "gear P": "기어 P (정차)"}
CLICK_LEAD = 0.35  # 패널 클릭은 이벤트 기록보다 화면에서 조금 먼저 보인다
LEAD = 0.7         # 장면 제목·클릭·엄마 자막을 앞당기는 시간
LEAD_SPEECH = 0.4  # 차 음성 자막을 앞당기는 시간
LEAD_CLICK = 0.1   # 클릭 자막은 화면 반응과 거의 같이 (너무 앞서면 태블릿이 늦어 보임)


def build_timeline(events):
    """이벤트에서 상단 장면(번호·제목·설명)과 하단 자막(클릭·엄마·차)을 만든다."""
    scenes, subs = [], []
    gear, drives, out_of_car, first_buckle, battery, charging = "P", 0, False, False, None, False

    def scene(t, title, result):
        scenes.append((max(t, 0), title, result))

    def say(t, text):
        subs.append((t + 0.2, "차", text, 0.8 + len(text) * 0.12))

    def mom(t, text):
        subs.append((max(t - 2.4, 0), "엄마", f"\u201c{text}\u201d", 2.4))

    for t, ev in events:
        k, a, txt = ev.get("kind"), ev.get("action") or ev.get("event"), ev.get("text") or ""
        if k == "car" and a == "panel":
            subs.append((t - CLICK_LEAD, "클릭", "주입 패널 · " + PANEL.get(txt, txt), 2.2))
            if txt == "belt left true" and not first_buckle:
                first_buckle = True
                scene(t - 1.0, "출발 전 · 아이가 스스로 벨트를 맵니다", "벨트 = 재생 버튼 → 영상 시작 · 칭찬 스티커 +1")
            elif txt == "belt left true" and out_of_car:
                out_of_car = False
                scene(t - 1.0, "휴게소 · 돌아와 스스로 벨트를 매면", "이어서 재생 · 칭찬 스티커 +1")
            elif txt == "belt left false" and gear == "D":
                scene(t - 1.0, "주행 중 · 장난감을 주우려 벨트를 풀면", "첫째 태블릿만 잠금 + 운전자에게 음성 알림 → 다시 매면 이어서")
                say(t, SPEECH["lock_drive"])
            elif txt == "gear P":
                gear = "P"
                scene(t - 1.0, "휴게소 도착 · 뒷문이 열려도", "벨트를 맨 채라면 아직 앉아 있음 → 계속 재생")
            elif txt == "gear D":
                gear = "D"; drives += 1
                if drives == 1:
                    scene(t - 1.0, "출발합니다", "기어 D → 운전석 화면은 좌석 카드와 마이크만")
        elif k == "car" and a == "getting_out":
            out_of_car = True
            scene(t - 0.2, "휴게소 · 아이가 벨트를 풀고 내리면", "벨트 해제 = 하차 → 잠금 · 문이 닫혀도 벨트를 매기 전엔 재생 안 함")
        elif k == "car" and a == "battery_low":
            battery = txt.split(":")[1] if ":" in txt else None
            scene(t + 2.5, "출발 전 · 태블릿 배터리 점검", "남은 시간 1시간 52분과 배터리를 비교해 차가 먼저 알림 · 물으면 숫자로 답함")
            say(t + 2.3, SPEECH["battery_low"])
        elif k == "car" and a == "battery_charging":
            charging = True
            subs.append((t - 1.6, "직접", "태블릿에 충전 케이블 연결", 1.6))
            say(t, SPEECH["battery_charging"])
        elif k == "car" and a == "voice_battery":
            mom(t, txt)
            line = f"첫째 태블릿은 지금 {battery}%예요. 도착까지 61%가 필요해요." + (" 지금 충전 중이에요." if charging else "")
            say(t, line)
        elif k == "car" and a == "voice_next":
            scene(t - 3.0, "주행 중 · \u201c엄마, 다른 거 틀어줘!\u201d", "부모 음성 한마디로 첫째 태블릿만 다음 편")
            mom(t, txt); say(t, SPEECH["voice_next"])
        elif k == "car" and a == "voice_volume_down":
            scene(t - 3.0, "주행 중 · \u201c소리가 너무 커요\u201d", "부모 음성으로 볼륨 낮춤")
            who = "첫째" if any(x in txt for x in ("첫", "큰")) else "두 태블릿"
            mom(t, txt); say(t, SPEECH["voice_volume_down"].format(who=who))
        elif k == "car" and a == "voice_remaining":
            scene(t - 3.0, "다시 출발 · \u201c엄마, 얼마나 남았어?\u201d", "차가 남은 시간을 소리로 답함")
            mom(t, txt); say(t, SPEECH["voice_remaining"])
        elif k == "car" and a == "speak":  # 앞으로 녹화분: 차가 실제로 한 말을 그대로 쓴다
            pass
    # 보는 사람이 화면 변화보다 먼저 읽을 수 있게 자막을 조금 앞당긴다 (차 음성은 소리와 거의 같이)
    scenes = [(max(t - LEAD, 0), a, b) for t, a, b in scenes]
    lead = {"차": LEAD_SPEECH, "클릭": LEAD_CLICK, "직접": LEAD_CLICK}
    subs = [(max(t - lead.get(tag, LEAD), 0), tag, text, n) for t, tag, text, n in subs]
    scenes.sort(key=lambda x: x[0]); subs.sort(key=lambda x: x[0])
    return scenes, subs


def run(cmd):
    subprocess.run(cmd, check=True)


def main():
    rec = Path(sys.argv[1]).resolve()
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else rec / "앞만봐_시연.mp4"
    start = int((rec / "start_ms").read_text().strip())
    dur = float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(rec / "car.mp4")]).decode().strip())
    tmp = rec / "overlay"; tmp.mkdir(exist_ok=True)

    text_png(tmp / "car_label.png", "운전석 화면 · Pleos Connect 에뮬레이터 (Vehicle SDK · Pleos STT/TTS)", 20, (139, 149, 165), bold=False)
    text_png(tmp / "tab_label.png", "뒷좌석 태블릿 · 갤럭시탭 실기기 (YouTube IFrame Player)", 20, (139, 149, 165), bold=False)
    text_png(tmp / "note1.png", "오른쪽 '시나리오 주입 패널'은 시연용입니다.", 18, (110, 120, 136), bold=False)
    text_png(tmp / "note2.png", "2열 벨트·뒷문·기어를 Vehicle SDK 콜백과 같은 함수로 입력합니다.", 18, (110, 120, 136), bold=False)
    text_png(tmp / "note3.png", "부모 음성은 실제 음성 인식(Pleos STT), 차 안내는 Pleos TTS.", 18, (110, 120, 136), bold=False)
    watermark_png(tmp / "wm.png")

    events = []
    ev_file = ROOT / "server" / "events.jsonl"
    for line in ev_file.read_text(encoding="utf-8").splitlines() if ev_file.exists() else []:
        ev = json.loads(line)
        t = (ev["ts"] - start) / 1000
        if 0 <= t <= dur:
            events.append((t, ev))
    # 첫 장면 4초 전부터, 마지막 장면 12초 뒤까지만 쓴다
    t0 = max(0.0, events[0][0] - 4) if events else 0.0
    t1 = min(dur, events[-1][0] + 12) if events else dur
    events = [(t - t0, ev) for t, ev in events]
    dur = t1 - t0
    scenes, subs = build_timeline(events)

    heads = []
    for i, (t, title, result) in enumerate(scenes):
        p = tmp / f"head{i}.png"
        header_png(p, i + 1, title, result)
        heads.append((p, t, scenes[i + 1][0] if i + 1 < len(scenes) else dur))
    if heads:  # 첫 장면 전에는 1번 장면을 미리 보여 준다
        heads[0] = (heads[0][0], 0.0, heads[0][2])
    # 두 줄: 위 = 손 동작(클릭·직접), 아래 = 말(엄마·차). 서로 가리지 않게 줄마다 따로 이어 붙인다
    caps = []
    for row, tags in ((0, ("클릭", "직접")), (1, ("엄마", "차"))):
        items = [x for x in subs if x[1] in tags]
        for j, (t, tag, text, length_) in enumerate(items):
            end = t + length_
            if j + 1 < len(items):
                end = min(end, max(items[j + 1][0], t + 1.0))
            p = tmp / f"sub{row}_{j}.png"
            w, h = sub_png(p, tag, text)
            caps.append((p, max(t, 0), min(end, dur), w, h, row))

    # 세 녹화(차·태블릿·마이크)는 0.1초 안쪽으로 맞으므로 같은 지점부터 자른다 (클릭 소리로 확인)
    ss = ["-ss", f"{t0:.2f}"]
    inputs = [*ss, "-i", str(rec / "car.mp4"), *ss, "-i", str(rec / "tablet.mp4")]
    has_audio = (rec / "audio.m4a").exists() and (rec / "audio.m4a").stat().st_size > 1000
    if has_audio:
        inputs += [*ss, "-i", str(rec / "audio.m4a")]
    fixed = [(tmp / "car_label.png", 40, 128), (tmp / "tab_label.png", 1150, 312), (tmp / "wm.png", W - 250, 26),
             (tmp / "note1.png", 1150, 800), (tmp / "note2.png", 1150, 828), (tmp / "note3.png", 1150, 856)]
    pngs = [f[0] for f in fixed] + [h[0] for h in heads] + [c[0] for c in caps]
    base = 3 if has_audio else 2
    for p in pngs:
        inputs += ["-loop", "1", "-i", str(p)]
    if not has_audio:
        inputs += ["-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo"]

    fg = [
        f"color=c=0x0E1116:s={W}x{H}:d={dur:.2f}[bg]",
        "[0:v]crop=1254:907:646:68,scale=1030:-2,setsar=1[car]",
        "[1:v]scale=740:-2,setsar=1[tab]",
        "[bg][car]overlay=40:158:shortest=1[v1]",
        "[v1][tab]overlay=1150:340[v2]",
    ]
    last, idx = "v2", base
    for i, (_, x, y) in enumerate(fixed):
        fg.append(f"[{last}][{idx}:v]overlay={x}:{y}:shortest=1[f{i}]"); last = f"f{i}"; idx += 1
    for i, (_, t, end) in enumerate(heads):
        fg.append(f"[{last}][{idx}:v]overlay=40:22:enable='between(t,{t:.2f},{end:.2f})':shortest=1[h{i}]"); last = f"h{i}"; idx += 1
    for j, (_, t, end, w, h, row) in enumerate(caps):
        y = 1062 - h if row == 1 else 1062 - 2 * h - 8
        fg.append(f"[{last}][{idx}:v]overlay=x=(W-{w})/2:y={y}:enable='between(t,{t:.2f},{end:.2f})':shortest=1[c{j}]"); last = f"c{j}"; idx += 1

    main_mp4 = tmp / "main.mp4"
    cmd = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", *inputs, "-filter_complex", ";".join(fg), "-map", f"[{last}]"]
    if has_audio:
        # 말소리는 살리고 갑자기 커지는 소리는 눌러서 찢어지지 않게
        cmd += ["-map", "2:a", "-af", "highpass=f=90,acompressor=threshold=0.05:ratio=4:attack=15:release=250:makeup=2,volume=2.5,alimiter=limit=0.89"]
    else:
        cmd += ["-map", f"{base + len(pngs)}:a"]
    cmd += ["-c:a", "aac", "-b:a", "160k", "-ar", "48000", "-ac", "2",
            "-t", f"{dur:.2f}", "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p", "-r", "30", str(main_mp4)]
    run(cmd)

    # 음성 명령: 말이 끝난 뒤 음성 인식이 끝을 판단하기까지(약 0.8초) 화면이 멈춰 있어 느려 보인다.
    # 그 대기 구간만 모든 트랙(차·태블릿·소리·자막)에서 같이 잘라낸다.
    if has_audio:
        cuts = []
        for t, ev in events:
            if ev.get("kind") == "car" and str(ev.get("event", "")).startswith("voice_"):
                lo = max(t - 3.0, 0)
                rms = subprocess.run(["ffmpeg", "-hide_banner", "-ss", f"{lo + t0:.2f}", "-t", "3.5", "-copyts", "-i", str(rec / "audio.m4a"),
                                      "-af", "asetnsamples=2400,astats=metadata=1:reset=1,ametadata=print:key=lavfi.astats.Overall.RMS_level", "-f", "null", "-"],
                                     capture_output=True, text=True).stderr
                frames, cur = [], 0.0
                for line in rms.splitlines():
                    if "pts_time:" in line:
                        cur = float(line.split("pts_time:")[1].split()[0]) - t0
                    elif "RMS_level=" in line:
                        v = line.split("RMS_level=")[1].strip()
                        frames.append((cur, -99.0 if v in ("-inf", "nan") else float(v)))
                # 말과 차 대답 사이의 가장 긴 조용한 구간(음성 인식 대기)을 찾는다
                best, run_start = (0, 0), None
                for tt, v in frames + [(frames[-1][0] + 0.05 if frames else 0, 0)]:
                    if v < -48:
                        run_start = tt if run_start is None else run_start
                    elif run_start is not None:
                        if tt - run_start > best[1] - best[0]:
                            best = (run_start, tt)
                        run_start = None
                a, b = best[0] + 0.15, best[1] - 0.15
                if b - a > 0.25:
                    cuts.append((a, b))
        if cuts:
            # 남길 구간만 trim/atrim으로 잘라 이어 붙인다 (영상·소리를 같은 시각으로 자르므로 싱크 유지)
            keep, prev = [], 0.0
            for a, b in sorted(cuts):
                keep.append((prev, a)); prev = b
            keep.append((prev, dur))
            parts, labels = [], ""
            for i, (a, b) in enumerate(keep):
                parts.append(f"[0:v]trim=start={a:.3f}:end={b:.3f},setpts=PTS-STARTPTS[v{i}]")
                parts.append(f"[0:a]atrim=start={a:.3f}:end={b:.3f},asetpts=PTS-STARTPTS[a{i}]")
                labels += f"[v{i}][a{i}]"
            parts.append(f"{labels}concat=n={len(keep)}:v=1:a=1[v][a]")
            cut_mp4 = tmp / "main_cut.mp4"
            run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", str(main_mp4), "-filter_complex", ";".join(parts),
                 "-map", "[v]", "-map", "[a]", "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p", "-r", "30",
                 "-c:a", "aac", "-b:a", "160k", str(cut_mp4)])
            print("음성 인식 대기 구간 잘라냄:", [f"{a:.1f}~{b:.1f}초 ({b - a:.1f}초)" for a, b in cuts])
            main_mp4 = cut_mp4

    card_png(tmp / "intro.png", "앞만봐 (Eyes Forward) · Pleos Connect 차량용 앱 시연", "토요일 오후, 아이를 태우고 강릉으로.\n운전은 엄마 혼자입니다.")
    card_png(tmp / "outro.png", "앞만봐 (Eyes Forward)", "운전자는 앞만 보고,\n아이는 스스로 벨트를 맵니다.", "차량 앱(Pleos Connect) · 중계 서버(WebSocket) · 태블릿 앱(갤럭시탭)")
    clips = []
    for name, sec in (("intro", 3.5), ("outro", 4.0)):
        c = tmp / f"{name}.mp4"
        run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-loop", "1", "-i", str(tmp / f"{name}.png"), "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo",
             "-t", str(sec), "-vf", "fade=t=in:st=0:d=0.5,fade=t=out:st=%.1f:d=0.5" % (sec - 0.5), "-c:v", "libx264", "-pix_fmt", "yuv420p", "-r", "30",
             "-c:a", "aac", "-b:a", "160k", "-ar", "48000", "-ac", "2", str(c)])
        clips.append(c)
    run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", str(clips[0]), "-i", str(main_mp4), "-i", str(clips[1]),
         "-filter_complex", "[0:v][0:a][1:v][1:a][2:v][2:a]concat=n=3:v=1:a=1[v][a]", "-map", "[v]", "-map", "[a]",
         "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k", str(out)])
    print(f"완성: {out} (본편 {dur:.1f}초 + 오프닝·엔딩, 장면 {len(heads)}개, 자막 {len(caps)}개, 소리 {'있음' if has_audio else '없음'})")


if __name__ == "__main__":
    main()

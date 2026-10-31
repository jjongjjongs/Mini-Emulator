#!/usr/bin/env python3
"""Builds app/src/main/assets/naomi.bin, the Naomi / Naomi 2 / Atomiswave set table the
app uses to recognise ROM ZIPs, pair GD-ROM games with their disc image, pick the
button layout and show Korean titles. It reads Flycast's own game list at the commit
cores/build-flycast.sh builds, so the table matches what the engine can run.

Usage: ./cores/gen-naomi.py

Output (gzip text; not named .gz because the Android build unpacks those):
  <set>\t<parent or ->\t<description>\t<naomi|naomi2|aw>\t<gd disc or ->\t<buttons>\t<1 when vertical>\t<Korean title or ->\t<ROM files in the ZIP, comma separated>
"""
import gzip, os, re, subprocess, sys, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "naomi.bin")
COMMIT = re.search(r"FLYCAST_COMMIT=([0-9a-f]+)", open(os.path.join(HERE, "build-flycast.sh")).read()).group(1)
RAW = "https://raw.githubusercontent.com/flyinghead/flycast/%s/" % COMMIT

# Korean titles by set name (clones use their parent's).
KOREAN = {
    "mvsc2": "마블 vs. 캡콤 2", "capsnk": "캡콤 vs. SNK", "capsnka": "캡콤 vs. SNK", "capsnkb": "캡콤 vs. SNK",
    "csnk2": "캡콤 vs. SNK 2", "cvs2gd": "캡콤 vs. SNK 2 (GD)", "cvs2": "캡콤 vs. SNK 2",
    "ggxx": "길티기어 XX", "ggxxrl": "길티기어 XX #리로드", "ggxxsla": "길티기어 XX 슬래시", "ggxxac": "길티기어 XX 액센트 코어",
    "ggx": "길티기어 X", "ggx15": "길티기어 X 1.5",
    "kofnw": "더 킹 오브 파이터즈 네오웨이브", "kofxi": "더 킹 오브 파이터즈 XI", "ngbc": "네오지오 배틀 콜리세움",
    "mslug6": "메탈슬러그 6", "samsptk": "사무라이 스피리츠 천하제일검객전", "fotns": "북두의 권", "rumblef": "럼블 피시", "rumblef2": "럼블 피시 2",
    "ikaruga": "이카루가", "radirgy": "라디르기", "radirgyo": "라디르기", "karous": "카로스", "trizeal": "트리질", "undefeat": "언더 디피트",
    "trghearto": "트리거하트 엑셀리카", "psyvar2": "사이바리아 2", "illvelo": "일벨로", "mamonoro": "마모노로", "shikgam2": "시키가미의 성 II",
    "senko": "선광의 윤무", "senkoo": "선광의 윤무", "senkosp": "선광의 윤무 SP", "bdrdown": "보더 다운", "chaoshea": "카오스 필드",
    "vf4": "버추어 파이터 4", "vf4b": "버추어 파이터 4", "vf4c": "버추어 파이터 4", "vf4evo": "버추어 파이터 4 에볼루션", "vf4evoa": "버추어 파이터 4 에볼루션",
    "vf4tuned": "버추어 파이터 4 파이널 튠드", "vf4tuneda": "버추어 파이터 4 파이널 튠드", "vf4tunedd": "버추어 파이터 4 파이널 튠드",
    "initd": "이니셜 D 아케이드 스테이지", "initdo": "이니셜 D 아케이드 스테이지", "initdv2j": "이니셜 D 아케이드 스테이지 Ver.2", "initdv3j": "이니셜 D 아케이드 스테이지 Ver.3",
    "initdv3e": "이니셜 D 아케이드 스테이지 Ver.3", "initdv2jo": "이니셜 D 아케이드 스테이지 Ver.2",
    "doa2": "데드 오어 얼라이브 2", "doa2m": "데드 오어 얼라이브 2 밀레니엄", "doa2a": "데드 오어 얼라이브 2",
    "pstone": "파워 스톤", "pstone2": "파워 스톤 2", "sfz3ugd": "스트리트 파이터 제로 3 어퍼", "spawn": "스폰",
    "crzytaxi": "크레이지 택시", "cspike": "캐논 스파이크", "csmash": "코스믹 스매시", "slashout": "슬래시 아웃", "toyfight": "토이 파이터",
    "jambo": "점보! 사파리", "zombrvn": "좀비 리벤지", "18wheelr": "18 휠러", "18wheels": "18 휠러", "18wheelu": "18 휠러",
    "dybbnao": "다이너마이트 야구 NAOMI", "dybb99": "다이너마이트 야구 '99", "vs2_2k": "버추어 스트라이커 2 Ver. 2000", "vtennis": "버추어 테니스", "vtennis2": "버추어 테니스 2",
    "vtennisg": "버추어 테니스", "vonot": "버추얼 온 오라토리오 탱그램", "otrigger": "아웃트리거", "ringout": "링 아웃 4x4", "gunsur2": "건 서바이버 2",
    "wldkicks": "월드 킥스", "wrungp": "웨이브 러너 GP", "hmgeo": "헤비 메탈 지오매트릭스", "marstv": "마스 TV", "sgtetris": "세가 테트리스", "puyoda": "뿌요뿌요 다!",
    "smlg99": "슈퍼 메이저 리그 '99", "samba": "삼바 데 아미고", "samba2k": "삼바 데 아미고 Ver.2000", "shangril": "샹그릴라", "ninjaslt": "닌자 어설트",
    "alienfnt": "에일리언 프론트", "mazan": "마잔", "sprtjam": "스포츠 잼", "f355": "F355 챌린지", "f355twin": "F355 챌린지 트윈", "f355twn2": "F355 챌린지 2",
    "tduno": "터치 데 UNO!", "tduno2": "터치 데 UNO! 2", "derbyoc": "더비 오너스 클럽", "derbyoc2": "더비 오너스 클럽 2", "derbyocw": "더비 오너스 클럽 월드",
    "gram2000": "자이언트 그램 2000", "ggram2": "자이언트 그램", "monkeyba": "슈퍼 몽키 볼", "wsbbgd": "월드 시리즈 베이스볼", "lupinsho": "루팡 3세 더 슈팅",
    "mushike": "갑충왕자 무시킹", "mushik2e": "갑충왕자 무시킹 2", "mushik4e": "갑충왕자 무시킹 4", "mushi2k": "갑충왕자 무시킹 2",
    "dolphin": "돌핀 블루", "azumanga": "아즈망가 대왕 퍼즐 보블", "bdrdown": "보더 다운", "cleoftp": "클레오파트라 포춘 플러스", "kurucham": "쿠루쿠루 치메",
    "sl2007": "스틸 러브 2007", "shorsepr": "쇼트 호스 프라이드", "quizqgd": "퀴즈 아 유 어 퀴즈?", "clubkrte": "클럽 카트",
    "soulsurf": "소울 서퍼", "cfield": "카오스 필드", "demofist": "데모리시 피스트", "dirtypig": "더티 피그스킨", "kov7sprt": "삼국전기 칠성전생",
    "maxspeed": "맥시멈 스피드", "ftspeed": "패스터 댄 스피드", "ggisuka": "길티기어 이스카", "rangrmsn": "레인저 미션", "sprtshot": "스포츠 슈팅 USA",
    "vfurlong": "넷 셀렉트 경마 빅토리 퍼롱", "xtrmhunt": "익스트림 헌팅", "xtrmhnt2": "익스트림 헌팅 2", "waidrive": "와이와이 드라이브", "blokpong": "블록 퐁퐁",
    "anmlbskt": "애니멀 바스켓", "salmankt": "살라리맨 금타로", "basschal": "배스 챌린지", "claychal": "클레이 챌린지",
    "inuwasi": "이누 와시", "ausfache": "아우스파헤", "toukon4": "신일본 프로레슬링 투혼열전 4", "wwfroyal": "WWF 로열 럼블", "virnba": "버추어 NBA",
    "kick4csh": "킥 4 캐시", "shaktam": "샤크타마", "shaktamb": "샤크타마", "crackndj": "크래킹 DJ", "crakndj2": "크래킹 DJ 파트 2",
    "tokyobus": "도쿄 버스 안내", "pokasuka": "포카스카 닌자", "mvsc2u": "마블 vs. 캡콤 2", "sstrkfgt": "세가 스트라이크 파이터",
    "meltyb": "멜티 블러드 액트 카덴차", "meltyba": "멜티 블러드 액트 카덴차", "meltybo": "멜티 블러드 액트 카덴차", "mbaa": "멜티 블러드 액트리스 어게인",
    "gundmgd": "기동전사 건담 연방 vs 지온", "gundmxgd": "기동전사 건담 연방 vs 지온 DX", "ss2005": "슈퍼 슈터 2005", "sfz3ugd": "스트리트 파이터 제로 3 어퍼",
    "usagiym": "우사기 야마시로 마히루", "tetkiwam": "테트리스 키와메미치", "kingrt66": "킹 오브 루트 66", "ngdup23a": "NAOMI DIMM 업데이트",
}


def fetch(path):
    with urllib.request.urlopen(RAW + path) as r:
        return r.read().decode("utf-8", "replace")


def main():
    roms = fetch("core/hw/naomi/naomi_roms.cpp")
    inputs = fetch("core/hw/naomi/naomi_roms_input.h")
    buttons = {}
    for m in re.finditer(r"static InputDescriptors (\w+)\s*=\s*INPUT_(\d)_BUTTON", inputs):
        buttons[m.group(1)] = int(m.group(2))
    for m in re.finditer(r"static InputDescriptors (\w+)\s*=\s*\{(.*?)\n\};", inputs, re.S):
        if m.group(1) not in buttons:
            buttons[m.group(1)] = len(re.findall(r"\{\s*(?:NAOMI|AWAVE)_BTN\d_KEY", m.group(2)))
    body = roms[roms.index("const Game Games[] ="):]
    pattern = re.compile(
        r'\{\s*\n\s*"([a-z0-9_]+)",\s*\n\s*(nullptr|"[a-z0-9_]+"),\s*\n\s*"([^"]*)",\s*\n\s*0x[0-9a-fA-F]+,\s*\n\s*'
        r'(?:0x[0-9a-fA-F]+|\d+),\s*\n\s*"([a-z0-9_\-]+)",(?:\s*//[^\n]*)?\s*\n\s*(M1|M2|M4|AW|GD),\s*\n\s*(ROT0|ROT270),'
        r'(.*?)\n\s*\},\s*\n\s*(nullptr|"[a-z0-9\-]+"),\s*\n\s*(?:&(\w+)|nullptr)', re.S)
    rows = []
    for name, parent, desc, bios, cart, rot, blobs, gd, inp in pattern.findall(body):
        files = []
        for blob in re.findall(r'\{\s*"([^"]+)"', blobs):
            if blob not in files:
                files.append(blob)
        system = "aw" if cart == "AW" else ("naomi2" if bios == "naomi2" else "naomi")
        parent = parent.strip('"') if parent != "nullptr" else "-"
        gd = gd.strip('"') if gd != "nullptr" else "-"
        count = buttons.get(inp, 4) if inp else 4
        korean = KOREAN.get(name) or (KOREAN.get(parent) if parent != "-" else None) or "-"
        rows.append((name, parent, desc, system, gd, str(max(1, min(6, count))), "1" if rot == "ROT270" else "0", korean, ",".join(files)))
    if len(rows) < 200:
        sys.exit("parsed only %d sets; the table format may have changed" % len(rows))
    with gzip.open(OUT, "wt", encoding="utf-8") as f:
        for row in rows:
            f.write("\t".join(row) + "\n")
    print("%d sets -> %s" % (len(rows), os.path.relpath(OUT)))


if __name__ == "__main__":
    main()

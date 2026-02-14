#!/usr/bin/env python3
"""BankTotal - 은행 잔액 확인 → 알림바 표시"""
import json, subprocess, re, datetime, os

TERMUX = "/data/data/com.termux/files/usr/bin"
SAVE_FILE = os.path.expanduser("~/.banktotal_balances.json")
SMS_LOG = os.path.expanduser("~/.bank_sms_log.json")

def run(cmd, timeout=15):
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return r.stdout.strip()
    except:
        return ""

def load_saved():
    try:
        with open(SAVE_FILE, "r") as f:
            return json.load(f)
    except:
        return {}

def save_balances(balances):
    with open(SAVE_FILE, "w") as f:
        json.dump(balances, f, ensure_ascii=False)

def main():
    saved = load_saved()
    balances = {}

    # 1) SMS 읽기 (오래된순 → 덮어쓰기로 최신 적용)
    raw = run([f"{TERMUX}/termux-sms-list", "-l", "1000", "-t", "inbox"], timeout=30)
    try:
        msgs = json.loads(raw)
    except:
        msgs = []

    for m in msgs:
        b = m.get("body", "")
        num = m.get("number", "").replace("-", "")
        if "잔액" not in b:
            continue
        x = re.search(r"잔액\s*([\d,]+)원?", b)
        if not x:
            continue
        bal = int(x.group(1).replace(",", ""))
        if num == "16449999":       # KB국민
            balances["KB국민"] = bal
        elif num == "15991111":     # 하나
            balances["하나"] = bal
        elif num == "15666000":     # 신협
            balances["신협"] = bal

    # 1-b) Tasker가 저장한 SMS 로그 읽기 (RCS 포함)
    try:
        with open(SMS_LOG, "r") as f:
            tasker_msgs = json.load(f)
    except:
        tasker_msgs = []

    for m in tasker_msgs:
        b = m.get("body", "")
        num = m.get("number", "").replace("-", "")
        if "잔액" not in b:
            continue
        x = re.search(r"잔액\s*([\d,]+)원?", b)
        if not x:
            continue
        bal = int(x.group(1).replace(",", ""))
        if num == "16449999":
            balances["KB국민"] = bal
        elif num == "15991111":
            balances["하나"] = bal
        elif num == "15666000":
            balances["신협"] = bal

    # 2) 알림 읽기 (최신순 → 첫 번째만 사용)
    raw = run([f"{TERMUX}/termux-notification-list"])
    try:
        notifs = json.loads(raw)
    except:
        notifs = []

    notif_found = set()
    for n in notifs:
        pkg = n.get("packageName", "")
        title = n.get("title", "")
        content = n.get("content", "")

        # 신협 (첫 번째만)
        if "cu.onbank" in pkg and "신협" not in notif_found and "잔액" in content:
            x = re.search(r"잔액\s*([\d,]+)원", content)
            if x:
                balances["신협"] = int(x.group(1).replace(",", ""))
                notif_found.add("신협")

        # 하나 (첫 번째만)
        if "hanapush" in pkg and "하나" not in notif_found and "잔액" in content:
            x = re.search(r"잔액\s*([\d,]+)원", content)
            if x:
                balances["하나"] = int(x.group(1).replace(",", ""))
                notif_found.add("하나")

        # 신한 (첫 번째만)
        if "shinhan" in pkg.lower() and "신한" not in notif_found:
            full = f"{title} {content}"
            if "잔액" in full:
                x = re.search(r"잔액\s*([\d.,]+)원", full)
                if x:
                    balances["신한"] = int(x.group(1).replace(".", "").replace(",", ""))
                    notif_found.add("신한")

    # 디버그: 파싱 결과 출력
    print("=== 디버그 ===")
    print(f"SMS 파싱: {balances}")
    print(f"저장된 값: {saved}")

    # 저장된 잔액에 새로 파싱한 값 덮어쓰기 (없으면 이전 값 유지)
    saved.update(balances)
    balances = saved
    save_balances(balances)

    print(f"최종 값: {balances}")
    print("==============")

    # 합산
    total = sum(balances.values())
    now = datetime.datetime.now().strftime("%m/%d %H:%M")

    # 알림 내용 (약어: 프라이버시)
    abbr = {"KB국민": "k", "하나": "ha", "신협": "s", "신한": "sh"}
    ntitle = f"{total:,}"
    lines = []
    for bank in ["KB국민", "하나", "신협", "신한"]:
        if bank in balances:
            lines.append(f"{abbr[bank]} {balances[bank]:,}")
        else:
            lines.append(f"{abbr[bank]} ---")
    lines.append(f"{now}")
    content = " | ".join(lines)

    # 알림 표시
    update_cmd = "bash /data/data/com.termux/files/home/banktotal.sh"
    run([
        f"{TERMUX}/termux-notification",
        "--id", "banktotal",
        "--title", ntitle,
        "--content", content,
        "--ongoing",
        "--priority", "low",
        "--alert-once",
        "--icon", "account_balance_wallet",
        "--button1", "업데이트",
        "--button1-action", update_cmd
    ])

    # 터미널 출력
    print(f"\n  {ntitle}")
    for bank in ["KB국민", "하나", "신협", "신한"]:
        if bank in balances:
            print(f"  {abbr[bank]:2s} {balances[bank]:>12,}")
    print(f"  {now}\n")

if __name__ == "__main__":
    main()

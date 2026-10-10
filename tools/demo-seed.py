#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""本地/演示环境种子数据（仅开发与演示用，禁止对生产库执行）。

只调用公开 HTTP API（注册、登录、绑机、发单、应征、聊天、选定、mock 支付、确认、交付物、验收、评价），
不直接写库，因此不绕过 ADR-0001（Flyway 唯一 DDL 来源）与任何业务门禁；产出的数据与真实用户操作完全一致。

前置条件（见 README「本地演示环境」）：
  - backend 以 dev profile 运行，且 wechat.pay.mock-enabled=true（否则支付步骤会失败）；
  - 配置了 MinIO/S3 兼容存储（MINIO_*），否则「交付物上传」相关场景会被跳过。

用法：
  python3 tools/demo-seed.py [--base http://127.0.0.1:8081] [--password Demo@2026] [--force]

  默认幂等：账号已存在则直接登录；演示客户名下已有需求时不再重复造单（--force 强制再造一套）。
  运行结束打印账号表与各场景的 taskNum，便于演示时直接定位。
只依赖 Python 3 标准库。
"""
import argparse
import base64
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ADMIN_HINT = "admin / 123456（AdminSeedInitializer 默认种子，可用 admin.seed.* 覆盖）"

CUSTOMER = "demo_kehu"
PILOTS = [
    # 用户名, DJI 设备号, 机型码, 资料
    ("pilot_liming", "DEMO-FC30-0001", "FC30",
     {"age": 34, "location": "浙江湖州", "selfIntroduction": "6 年山区吊运经验，FlyCart 30 机长，熟悉基站与光伏建材吊装。",
      "idNumber": "330501199201011234", "idExpiryDate": "2036-05-01", "grade": "BVLOS"}),
    ("pilot_zhaoqiang", "DEMO-M350-0002", "M350RTK",
     {"age": 29, "location": "浙江杭州", "selfIntroduction": "测绘出身，M350 RTK 精准投放，擅长小件设备与应急物资。",
      "idNumber": "110105199003071239", "idExpiryDate": "2034-09-30", "grade": "VLOS"}),
    ("pilot_chenyu", "DEMO-FC30-0003", "FC30",
     {"age": 41, "location": "浙江丽水", "selfIntroduction": "AOPA 教员，带队完成多次林区物资吊运。",
      "idNumber": "330102198207151238", "idExpiryDate": "2031-12-31", "grade": "TEACHER"}),
]

# 安吉县一带的起吊点/卸货点（经度, 纬度）
SPOTS = {
    "town": (119.6800, 30.6380),
    "hilltop": (119.6950, 30.6505),
    "farm": (119.6620, 30.6210),
    "village": (119.7100, 30.6290),
    "station": (119.7015, 30.6602),
}


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")

    def call(self, method, path, token=None, body=None, query=None, expect_ok=True):
        url = self.base + path
        if query:
            url += ("&" if "?" in url else "?") + urllib.parse.urlencode(query)
        data = None
        headers = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
        for attempt in range(6):
            req = urllib.request.Request(url, data=data, headers=headers, method=method)
            try:
                with urllib.request.urlopen(req, timeout=20) as resp:
                    payload = json.loads(resp.read().decode("utf-8") or "{}")
            except urllib.error.HTTPError as e:
                raw = e.read().decode("utf-8", "replace")
                try:
                    payload = json.loads(raw)
                except ValueError:
                    payload = {"success": False, "code": e.code, "message": raw[:200]}
                if e.code == 429 and attempt < 5:
                    # 登录/注册/聊天等接口有按分钟的限流（RateLimiterAspect），等窗口滑过再重试
                    print(f"  [限流] {method} {path}，15 秒后重试…")
                    time.sleep(15)
                    continue
            break
        if expect_ok and not payload.get("success"):
            raise RuntimeError(f"{method} {path} 失败: {payload.get('code')} {payload.get('message')}")
        return payload

    def put_bytes(self, url, content, content_type):
        req = urllib.request.Request(url, data=content, headers={"Content-Type": content_type}, method="PUT")
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.status


def user_id(token):
    part = token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4))).get("userId")


def login_or_register(api, name, password, rider=False):
    res = api.call("POST", "/user/login", body={"userName": name, "password": password}, expect_ok=False)
    if res.get("success"):
        return res["data"]["token"], False
    if rider:
        # 飞手注册直接签发令牌，省一次登录（登录接口按 IP 限流 5 次/分钟）
        return api.call("POST", "/rider/register", body={"userName": name, "password": password})["data"]["token"], True
    api.call("POST", "/user/register", body={"userName": name, "password": password})
    res = api.call("POST", "/user/login", body={"userName": name, "password": password})
    return res["data"]["token"], True


def waypoints(a, b):
    (lng1, lat1), (lng2, lat2) = SPOTS[a], SPOTS[b]
    return [{"orderIndex": 0, "longitude": lng1, "latitude": lat1, "altitude": 0},
            {"orderIndex": 1, "longitude": lng2, "latitude": lat2, "altitude": 0}]


def create_task(api, token, name, desc, category, weight, frm, to):
    body = {"taskName": name, "type": "TRANSPORT", "description": desc, "reward": 0,
            "cargoCategory": category, "cargoWeightKg": weight, "waypoints": waypoints(frm, to)}
    return api.call("POST", "/task/create", token, body)["data"]


def apply(api, token, task_num, model_id):
    return api.call("POST", "/rider/apply", token, body={"taskNum": task_num, "aircraftModelId": model_id},
                    query={"taskNum": task_num, "aircraftModelId": model_id})["data"]


def chat(api, customer, pilot, task_num, app_id, lines):
    session = api.call("POST", "/chat/session/create", customer, body={
        "name": "订单洽谈", "type": 0, "userIds": [user_id(customer), user_id(pilot)],
        "taskNum": task_num, "applicationId": app_id})["data"]
    sid = session.get("id") or session.get("sessionId")
    for who, text in lines:
        api.call("POST", "/chat/Message/send", customer if who == "c" else pilot,
                 body={"sessionId": sid, "content": text})
    return sid


def select_and_pay(api, customer, task, app_id, when):
    api.call("POST", f"/task/{task['taskNum']}/select-rider", customer,
             body={"applicationId": app_id, "scheduledTime": when})
    api.call("POST", f"/pay/{task['orderNum']}", customer)


def upload_evidence(api, pilot, task_num):
    # 1x1 PNG：真实走 presigned PUT，交付物列表与监管端可直接预览
    png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
    for fname in ("吊运完成-卸货点.png", "绑扎与吊点.png"):
        cred = api.call("POST", f"/tasks/{task_num}/attachments/upload-url", pilot,
                        query={"fileName": fname, "contentType": "image/png", "sizeBytes": len(png)})["data"]
        api.put_bytes(cred["uploadUrl"], png, "image/png")


def main():
    ap = argparse.ArgumentParser(description="飞翼通 本地/演示环境种子数据（经公开 API 造数）")
    ap.add_argument("--base", default="http://127.0.0.1:8081", help="backend 基址")
    ap.add_argument("--password", default="Demo@2026", help="演示账号统一密码")
    ap.add_argument("--force", action="store_true", help="演示客户已有需求时仍再造一套场景")
    args = ap.parse_args()
    api = Api(args.base)
    pw = args.password

    customer, created = login_or_register(api, CUSTOMER, pw)
    print(f"[账号] 客户 {CUSTOMER} {'新建' if created else '已存在'}")
    models = {m["modelCode"]: m["id"] for m in api.call("GET", "/api/aircraft-models", customer)["data"]}

    pilots = {}
    for name, dji, code, info in PILOTS:
        token, created = login_or_register(api, name, pw, rider=True)
        drones = api.call("GET", "/rider/drone/list", token)["data"] or []
        if not any(d.get("djiId") == dji for d in drones):
            api.call("POST", "/rider/drone/bind", token, query={"djiId": dji, "aircraftModelId": models[code]})
        api.call("PATCH", "/rider/info", token, body={
            "age": info["age"], "location": info["location"], "selfIntroduction": info["selfIntroduction"],
            "idNumber": info["idNumber"], "idExpiryDate": info["idExpiryDate"],
            "qualifications": [{"aircraftModelId": models[code], "licenseGrade": info["grade"]}]})
        pilots[name] = (token, models[code])
        print(f"[账号] 飞手 {name}（{code}）{'新建' if created else '已存在'}")

    existing = api.call("GET", "/task/list", customer, query={"page": 1, "size": 1})["data"]
    total = existing.get("total", 0) if isinstance(existing, dict) else len(existing or [])
    if total and not args.force:
        print(f"[跳过] {CUSTOMER} 名下已有 {total} 个需求；如需再造一套场景请加 --force")
        return print_accounts(pw)

    li, li_m = pilots["pilot_liming"]
    zhao, zhao_m = pilots["pilot_zhaoqiang"]
    chen, chen_m = pilots["pilot_chenyu"]
    storage_ok = True
    scenes = []

    t = create_task(api, customer, "茶园有机肥吊运上山", "有机肥 10 袋，每袋 2 kg，卸在茶园平台东侧。", "AGRICULTURAL", 20, "farm", "hilltop")
    scenes.append(("招募飞手（暂无应征）", t["taskNum"]))

    t = create_task(api, customer, "基站备用电池吊运", "2 组备用电池，已装箱，需轻放；现场有人接应。", "EQUIPMENT", 2.5, "town", "station")
    a_li = apply(api, li, t["taskNum"], li_m)
    a_zhao = apply(api, zhao, t["taskNum"], zhao_m)
    chat(api, customer, zhao, t["taskNum"], a_zhao["applicationId"],
         [("c", "你好，周六上午能飞吗？电池箱大概 50cm 见方。"), ("p", "可以，M350 吊这个重量没问题，吊点我会用防滑绑带。"),
          ("c", "好的，我再比较一下报价。")])
    scenes.append((f"洽谈中（2 个应征：FC30 ¥{a_li['quotedAmount']} / M350 ¥{a_zhao['quotedAmount']}）", t["taskNum"]))

    t = create_task(api, customer, "山顶光伏板支架吊运", "铝合金支架 4 捆，单捆约 6 kg。", "CONSTRUCTION", 24, "town", "hilltop")
    a = apply(api, li, t["taskNum"], li_m)
    chat(api, customer, li, t["taskNum"], a["applicationId"], [("c", "下周一 9 点开工可以吗？"), ("p", "没问题，我提前半小时到起吊点。")])
    select_and_pay(api, customer, t, a["applicationId"], "2030-10-14 09:00:00")
    scenes.append(("待飞手确认（已选定并支付）", t["taskNum"]))

    t = create_task(api, customer, "村委会防汛物资转运", "矿泉水与应急包，共约 18 kg。", "DAILY_SUPPLIES", 18, "town", "village")
    a = apply(api, chen, t["taskNum"], chen_m)
    select_and_pay(api, customer, t, a["applicationId"], "2030-10-12 14:00:00")
    api.call("POST", "/rider/confirm-order", chen, query={"taskNum": t["taskNum"]})
    scenes.append(("进行中（双方已确认，待执飞）", t["taskNum"]))

    t = create_task(api, customer, "民宿屋顶家具吊装", "户外桌椅一套，已拆分打包，约 22 kg。", "FURNITURE", 22, "village", "hilltop")
    a = apply(api, li, t["taskNum"], li_m)
    select_and_pay(api, customer, t, a["applicationId"], "2030-10-10 10:00:00")
    api.call("POST", "/rider/confirm-order", li, query={"taskNum": t["taskNum"]})
    try:
        upload_evidence(api, li, t["taskNum"])
        api.call("POST", "/rider/complete", li, query={"taskNum": t["taskNum"], "note": "已吊装至屋顶平台，客户现场签收。"})
        scenes.append(("待验收（飞手已上传证据并交付）", t["taskNum"]))
    except Exception as e:  # noqa: BLE001 - 存储未配置时降级
        storage_ok = False
        print(f"[降级] 交付物上传失败（{e}），跳过待验收/已完成场景；请配置 MINIO_*")
        scenes.append(("进行中（交付物存储未配置，未能交付）", t["taskNum"]))

    if storage_ok:
        t = create_task(api, customer, "林场水泥吊运", "水泥 1 袋 25 kg 分两次吊运。", "CONSTRUCTION", 25, "farm", "station")
        a = apply(api, chen, t["taskNum"], chen_m)
        select_and_pay(api, customer, t, a["applicationId"], "2030-10-08 08:30:00")
        api.call("POST", "/rider/confirm-order", chen, query={"taskNum": t["taskNum"]})
        upload_evidence(api, chen, t["taskNum"])
        api.call("POST", "/rider/complete", chen, query={"taskNum": t["taskNum"], "note": "两趟完成，已拍照留证。"})
        api.call("POST", "/task/confirm", customer, query={"taskNum": t["taskNum"]})
        api.call("POST", "/review/submit", customer, query={"orderNum": t["orderNum"], "rating": 5, "content": "准时到场，吊装平稳，推荐！"})
        scenes.append(("已完成（已验收并评价）", t["taskNum"]))

    print("\n[场景]")
    for label, num in scenes:
        print(f"  - {label}: {num}")
    print_accounts(pw)


def print_accounts(pw):
    print("\n[账号]（密码统一为 %s）" % pw)
    print(f"  客户（发单用户）: {CUSTOMER}")
    for name, _, code, _ in PILOTS:
        print(f"  飞手: {name}（{code}）")
    print(f"  监管端管理员: {ADMIN_HINT}")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:  # noqa: BLE001
        print(f"[失败] {exc}", file=sys.stderr)
        sys.exit(1)

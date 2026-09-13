import Foundation

// 离线校验 YAPI 响应解析与 JSON Schema → 示例 的转换结果（不联网）

func envelope(_ data: [String: Any]) -> String {
    let root: [String: Any] = ["errcode": 0, "errmsg": "成功！", "data": data]
    let d = try! JSONSerialization.data(withJSONObject: root, options: [.withoutEscapingSlashes])
    return String(data: d, encoding: .utf8)!
}

func run(_ name: String, _ text: String, expectSample: String? = nil) {
    do {
        let f = try YapiAPI.parseResponse(text, fallbackId: "0")
        print("===== \(name) =====")
        print("id=\(f.id) title=\(f.title) \(f.summary) ct=\(f.contentType) json=\(f.isJson) project=\(f.projectId ?? "-")")
        print(f.body)
        if let e = expectSample {
            print(f.body == e ? "✅ 示例与预期一致" : "❌ 与预期不一致：\n\(e)")
        }
        print("")
    } catch {
        print("===== \(name) ===== 失败：\((error as? YapiError)?.message ?? "\(error)")\n")
    }
}

// 1) JSON Schema（YAPI 最常见形态）
let schema = #"""
{
  "type": "object",
  "properties": {
    "code": { "type": "integer", "description": "返回码" },
    "message": { "type": "string" },
    "list": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "id": { "type": "integer" },
          "name": { "type": "string" },
          "enable": { "type": "boolean" },
          "tags": { "type": "array", "items": { "type": "string" } },
          "createTime": { "type": "string", "format": "date-time" }
        }
      }
    },
    "total": { "type": "integer", "example": 10 },
    "emptyObj": { "type": "object", "properties": {} },
    "nested": { "type": "object", "properties": { "inner": { "type": "string", "enum": ["a", "b"] } } }
  },
  "required": ["code", "message"]
}
"""#

let expectSchema = #"""
{
  "code" : 0,
  "message" : "",
  "list" : [
    {
      "id" : 0,
      "name" : "",
      "enable" : false,
      "tags" : [
        ""
      ],
      "createTime" : "2020-01-01 00:00:00"
    }
  ],
  "total" : 10,
  "emptyObj" : {},
  "nested" : {
    "inner" : "a"
  }
}
"""#

run("schema 接口",
    envelope(["title": "获取配置列表", "method": "GET", "path": "/configsvr/serverlist.jsp",
              "status": "done", "res_body_type": "json", "res_body_is_json_schema": true,
              "res_body": schema, "project_id": 1378, "_id": 189868]),
    expectSample: expectSchema)

// 2) raw 类型：res_body 是字面示例文本
run("raw 接口",
    envelope(["title": "健康检查", "method": "POST", "path": "/health", "res_body_type": "raw",
              "res_body": "{\n  \"ok\": true,\n  \"ts\": 1712345678\n}", "_id": 200001]))

// 3) YAPI 里写的是字面 JSON 示例（不是 schema）
run("字面量示例",
    envelope(["title": "示例字面量", "method": "GET", "path": "/literal", "res_body_type": "json",
              "res_body_is_json_schema": false, "res_body": #"{"b":2,"a":1,"nested":{"z":1,"y":2}}"#, "_id": 200002]))

// 4) 接口没配响应示例
run("空 res_body",
    envelope(["title": "无示例", "method": "GET", "path": "/nothing", "res_body_type": "json",
              "res_body": "", "_id": 200003]))

// 5) schema 根是数组
run("数组根 schema",
    envelope(["title": "列表", "method": "GET", "path": "/arr", "res_body_type": "json",
              "res_body": #"{"type":"array","items":{"type":"object","properties":{"a":{"type":"string"}}}}"#, "_id": 200004]))

// 6) token 失效
run("token 失效", #"{"errcode":40011,"errmsg":"登录失效，请重新登录"}"#)

// 7) 被登录页拦截（HTML）
run("HTML 拦截", "<!DOCTYPE html><html><body>login</body></html>")

// 8) 保序 + 美化 / 压缩
let messy = #"{"z":1,"a":{"c":3,"b":[1,2,{"k":"v"}]},"emoji":"好\u4e86","esc":"line\nnext","n":1.5e3,"flag":true,"nil":null}"#
if let node = parseOrderedJSON(messy) {
    print("===== 美化（保序） =====")
    print(renderJSON(node))
    print("\n===== 压缩 =====")
    print(renderJSON(node, pretty: false))
    print("\n键顺序：", node.objectPairs?.map { $0.0 } ?? [], "\n")
}
print("非法 JSON 校验：", parseOrderedJSON("{\"a\":}") == nil ? "已拒绝 ✓" : "误判 ✗")
print("尾随垃圾校验：", parseOrderedJSON("{\"a\":1} xxx") == nil ? "已拒绝 ✓" : "误判 ✗")

// 9) Cookie 组装 / 地址规范化
print("\n纯 token     ->", YapiAPI.cookieHeader(token: "abc.def", uid: "4472") ?? "-")
print("token 无 uid ->", YapiAPI.cookieHeader(token: "abc.def", uid: "") ?? "-")
print("整段 Cookie  ->", YapiAPI.cookieHeader(token: "_yapi_token=eyFLX; _yapi_uid=4472", uid: nil) ?? "-")
print("服务地址     ->", YapiAPI.normalizeService("stp.haier.net/api/interface/get?id=1"))

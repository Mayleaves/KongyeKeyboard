"""Format the device candidate report without inventing ground-truth word frequencies."""
from pathlib import Path
import argparse
import json


def format_report(result):
    lines = ["# 拼音候选专项测试结果", "", "前三候选是评审参考，不固定同音常用词的唯一顺序；唯一合理结果不足三个时不凑数。", ""]
    cases = result["cases"]
    failures = sum(len(c.get("failures", [])) for c in cases)
    reviews = sum(bool(c.get("review")) for c in cases)
    lines += [f"共 {len(cases)} 条用例，规则错误 {failures} 项，排名或覆盖待复核 {reviews} 条。", ""]
    if result.get("settingsPreserved"):
        lines += ["设置与个人学习文件保持不变；测试期间关闭学习权重。", ""]
    for case in cases:
        reference = "、".join(case["expectedTop3"]) or "无唯一语义预期"
        actual = "、".join(c["word"] for c in case.get("candidates", [])[:3]) or "无结果"
        watch = "、".join(case.get("watch", [])) or "按读音、配置与上下文检查，不设置全局禁词"
        lines += [f"## {case['id']} · {case['category']}", "",
                  f"输入串：`{case['input']}`｜预期合理候选前3位：{reference}｜需要警惕的靠前候选：{watch}", "",
                  f"实际前3位：{actual}", "",
                  f"配置：模糊音 {','.join(case.get('fuzzy', [])) or '关闭'}；纠错位掩码 {case.get('correction', 0)}。", "",
                  f"评审依据：{case['reason']}", ""]
        if "totalMs" in case:
            first = case.get("firstBatchMs")
            lines += [f"首批回调：{first if first is not None else '无单独回调'} ms；完整查询：{case['totalMs']} ms。", ""]
        for status, key in [("规则错误", "failures"), ("待复核", "review")]:
            lines += [f"- {status}：{item}" for item in case.get(key, [])]
        if case.get("failures") or case.get("review"):
            lines += [""]
        lines += ["| 排名 | 候选 | 读音 | 消费字母数 | 来源 |", "| --- | --- | --- | --- | --- |"]
        for rank, candidate in enumerate(case.get("candidates", [])[:10], 1):
            lines.append(f"| {rank} | {candidate['word'].replace('|', '/')} | {candidate['reading']} | {candidate['consumed']} | {candidate['source']} |")
        lines += [""]
        if case.get("prefixTrace"):
            lines += ["| 逐段输入 | 显示切分 | 首选 | 消费字母数 | 完整查询 ms |", "| --- | --- | --- | --- | --- |"]
            for step in case["prefixTrace"]:
                first = next(iter(step["candidates"]), {})
                lines.append(f"| {step['input']} | {step['display']} | {first.get('word', '')} | {first.get('consumed', '')} | {step['totalMs']} |")
            lines += [""]
    return "\n".join(lines)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--cases", type=Path, default=Path(__file__).resolve().parents[1] / "app/src/androidTest/assets/pinyin-adversarial-v1.json")
    args = parser.parse_args()
    result = json.loads(args.input.read_text(encoding="utf8"))
    reference = {c["id"]:c for c in json.loads(args.cases.read_text(encoding="utf8"))["cases"]}
    for case in result["cases"]:
        case["watch"] = reference[case["id"]].get("watch", [])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(format_report(result), encoding="utf8")
    print(f"Wrote {len(result['cases'])} reviewed cases to {args.output}")

#!/bin/bash
# 从 awesome-prometheus-alerts 导入告警知识到 RAG 知识库
# 用法: bash import_alerts.sh
# 前置: git 已安装, Agent 在 localhost:8081 运行

AGENT="http://localhost:8081"

echo ">>> 克隆 awesome-prometheus-alerts..."
git clone --depth 1 https://github.com/samber/awesome-prometheus-alerts.git /tmp/prom-alerts 2>/dev/null
cd /tmp/prom-alerts || exit 1

for RULE_FILE in \
  _data/rules/mysql-8x.yml \
  _data/rules/oliver006-redis-exporter.yml \
  _data/rules/host-and-hardware-node-exporter.yml \
  _data/rules/jvm-actuator.yml
do
  [ ! -f "$RULE_FILE" ] && echo "跳过: $RULE_FILE (不存在)" && continue
  echo ">>> 处理: $RULE_FILE"

  python3 - "$RULE_FILE" <<'PY'
import sys, yaml, json, re, urllib.request

with open(sys.argv[1]) as f:
    docs = yaml.safe_load_all(f.read())

for doc in docs:
    if not doc or 'groups' not in doc: continue
    for group in doc['groups']:
        for rule in group.get('rules', []):
            alert = rule.get('alert', '')
            summary = rule.get('annotations', {}).get('summary', '')
            desc = rule.get('annotations', {}).get('description', '')
            if not alert: continue
            title = alert
            content = f"告警名称: {alert}\n告警描述: {summary}\n详细说明: {desc}\n严重级别: {rule.get('labels',{}).get('severity','unknown')}"
            # POST to RAG API
            import urllib.parse
            data = f"title={urllib.parse.quote(title)}&content={urllib.parse.quote(content[:500])}"
            req = urllib.request.Request(f"http://localhost:8081/rag/add", data=data.encode(), method='POST')
            try:
                urllib.request.urlopen(req)
                print(f"  + {alert}")
            except Exception as e:
                print(f"  x {alert}: {e}")
PY

done

echo ">>> 完成! 清理..."
rm -rf /tmp/prom-alerts

# ============================================================
# 精简镜像：只需要 Node 运行时 + 源码，零第三方依赖
# ============================================================
# 这个项目没有 npm 依赖树（数据库用 node:sqlite，密码哈希用内置 crypto），
# 所以镜像里不需要 npm install，也没有 node_modules。
# 攻击面极小。

# ---- 构建阶段：只用 node:sqlite 做一次语法自检，挡住低级错误 ----
FROM node:22-alpine AS check
WORKDIR /app
COPY package.json server.js db.js moderation.js moderation_server.js order_table.js xlsx_util.js ./
# --check 只做语法解析，不执行代码，所以不会真的建库
RUN for f in server.js db.js moderation.js moderation_server.js order_table.js xlsx_util.js; do \
      node --check "$f" || exit 1; \
    done && echo "语法自检通过"

# ---- 运行阶段 ----
FROM node:22-alpine

# 时区（开单时间、账期都按本地时间算）
RUN apk add --no-cache tzdata && \
    ln -snf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone

# tini：node 不处理 SIGTERM，没有它docker stop 会等10 秒强杀，
# 正在写的 SQLite 事务可能被截断（最坏情况丢一个订单）。
RUN apk add --no-cache tini

WORKDIR /app

COPY --from=check /app/*.js ./
COPY package.json ./
COPY public ./public

# data/ 与 uploads/ 通过 volume 挂载；这里先建好并交给非 root 用户
RUN mkdir -p data uploads/feedback tls && \
    addgroup -g 1001 erp && adduser -D -u 1001 -G erp erp && \
    chown -R erp:erp /app

USER erp

ENV NODE_ENV=production \
    PORT=3000 \
    ERP_BIND=0.0.0.0

EXPOSE 3000

ENTRYPOINT ["/sbin/tini", "--"]
CMD ["node", "server.js"]
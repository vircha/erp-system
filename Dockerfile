# ============================================================
# 精简镜像：只需要 Node 运行时 + 源码，零第三方依赖
# ============================================================
# 这个项目没有 npm 依赖树（数据库用 node:sqlite，密码哈希用内置 crypto），
# 所以镜像里不需要 npm install，也没有 node_modules。
# 攻击面极小。
#
# 🔴 构建上下文由 .dockerignore 白名单控制。
#    新增源码文件时必须同步更新 .dockerignore，否则 `COPY` 会报文件找不到
#    （会立刻暴露的错误，不会静默漏文件）。

# ---- 构建阶段：语法自检 + node:sqlite 可用性实测，挡住低级错误 ----
FROM node:22-alpine AS check
WORKDIR /app
COPY package.json server.js db.js moderation.js moderation_server.js order_table.js xlsx_util.js ./
# --check 只做语法解析，不执行代码，所以不会真的建库
RUN for f in server.js db.js moderation.js moderation_server.js order_table.js xlsx_util.js; do \
      node --check "$f" || exit 1; \
    done && echo "语法自检通过"

# 🔴🔴 必须在构建期实测 node:sqlite 在这个基础镜像里真的能 import。
#
#   为什么要实测而不是相信「node:22-alpine 自带」：
#   node:sqlite 是 Node 22.5 才加进来的内置模块，且**编译期决定有没有**。
#   它在 alpine(musl) 上并非官方保证随镜像提供；一旦基础镜像换版本 /
#   换变体，构建出的镜像会在**启动瞬间**才崩：
#       Error: Cannot find module 'node:sqlite'
#   而这个镜像不是给本项目用的，是推给 GHCR 给别人部署的 ——
#   别人拉下来第一件事就是 docker compose up，然后起不来。
#   在 CI 里 build 就炸，比在别人生产上炸便宜一百倍。
#
#   只读内存库、不碰 /app/data：这里只验「模块能不能加载」。
RUN node -e "require('node:sqlite'); console.log('node:sqlite 可用, SQLite ' + process.versions.sqlite)"

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
# 🔴 权限铁律：容器内以非 root（erp, uid 1001）运行，
#    所以挂载进来的目录属主必须是 1001:1001，否则 SQLite 打开数据库会 EACCES。
#    compose 用的是 ./data 绑定挂载（不是命名卷）——绑定挂载会**覆盖**镜像里
#    建好的目录与属主，所以宿主机上要自己 chown，deploy.sh 里已经做了这一步。
#    自建部署的人如果直接 docker compose up 报 permission denied，
#    就是这一条：sudo chown -R 1001:1001 ./data ./uploads
RUN mkdir -p data uploads/feedback tls && \
    addgroup -g 1001 erp && adduser -D -u 1001 -G erp erp && \
    chown -R erp:erp /app

USER erp

ENV NODE_ENV=production \
    PORT=3000 \
    ERP_BIND=0.0.0.0

# OCI 标准标签：docker inspect / 各云平台的镜像仓库都会读这三个。
# 由 CI 在 build 时用 --build-arg 注入（arg 默认值保留，本地 build 也不会失败）。
ARG ERP_VERSION=dev
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown
LABEL org.opencontainers.image.title="ERP System" \
      org.opencontainers.image.description="门店进销存：开单、小票打印、多门店欠款对账" \
      org.opencontainers.image.source="https://github.com/vircha/erp-system" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.version="${ERP_VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}"

EXPOSE 3000

# 🔴 容器级健康检查（compose 里也有一份，作用不同：那份管的是
#    「erp 起来没、caddy 能不能依赖它」，这份管的是裸 docker run 的场景）
#    /api/me 未登录会返 401，说明 HTTP 链路和路由都通；只有 >=500 才算不健康。
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD node -e "require('http').get('http://127.0.0.1:'+(process.env.PORT||3000)+'/api/me',r=>process.exit(r.statusCode<500?0:1)).on('error',()=>process.exit(1))"

ENTRYPOINT ["/sbin/tini", "--"]
CMD ["node", "server.js"]
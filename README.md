# grpc-web-sb

Kotlin + Spring Boot + Spring gRPC の gRPC API を、**Envoy などの外部プロキシなしで**ブラウザから直接呼べるようにするサンプル。
Spring Boot アプリ内に gRPC-Web → gRPC ブリッジを持ち、TypeScript フロントは Connect-ES の gRPC-Web transport で型付き呼び出しを行う。

```
browser ──gRPC-Web (HTTP/1.1 or 2)──▶ :8080 /grpc-web/{Service}/{Method}  (Spring MVC: GrpcWebController)
                                              │ in-process channel (生バイト転送)
native gRPC client ──gRPC (HTTP/2)──▶ :9090 ──┴──▶ Spring gRPC server ──▶ GreeterService
```

ブリッジは in-process サーバ経由で呼ぶため、サービス実装と `ServerInterceptor` (認証・ログ等) はネイティブ gRPC と共通。
メッセージをデシリアライズせずバイト列で転送するので、サービスを追加してもブリッジ側のコード変更は不要。

## 構成 (モノレポ)

| パス | 内容 |
| --- | --- |
| `proto/` | 共有契約。backend (Gradle protobuf plugin) と frontend (buf) の両方がここから生成 |
| `backend/` | Kotlin + Spring Boot 4.0 + Spring gRPC 1.0 |
| `backend/.../grpcweb/` | gRPC-Web ブリッジ (`GrpcWebController`, `GrpcWebFrames`, `GrpcWebConfig`) |
| `frontend/` | Vite + TypeScript (フレームワーク無し) + Connect-ES v2 |
| `buf.yaml`, `buf.gen.yaml` | proto lint / TS 生成設定 |

## 動かし方

```bash
./gradlew :backend:bootRun
```

```bash
pnpm install
pnpm gen
pnpm dev
```

http://localhost:5173 を開く。Vite の dev proxy が `/grpc-web` を `:8080` に転送する。

curl でも確認できる (`printf` で 1 フレーム分のリクエストを作る):

```bash
printf '\x00\x00\x00\x00\x07\x0a\x05World' | curl -s -X POST localhost:8080/grpc-web/hello.v1.GreeterService/SayHello -H 'content-type: application/grpc-web+proto' --data-binary @- | xxd
```

## テスト

```bash
./gradlew build
```

```bash
pnpm build
```

## ブリッジの仕様

- `POST {grpc-web.path}/{package.Service}/{Method}`
- Content-Type: `application/grpc-web[+proto]`, `application/grpc-web-text[+proto]`
- 対応: unary / server streaming (gRPC-Web の仕様上 client / bidi streaming は不可 → `UNIMPLEMENTED`)
- server streaming はフレームごとに flush するので逐次ブラウザに届く
- エラーは HTTP 200 + trailer frame の `grpc-status` / `grpc-message`
- リクエストヘッダは gRPC メタデータとして転送 (`authorization`, `x-*` など。`-bin` は base64 デコード)
- `grpc-timeout` はデッドラインに変換
- 圧縮リクエストは未対応

設定 (`backend/src/main/resources/application.yaml`):

```yaml
spring.grpc.server:
  port: 9090                     # ネイティブ gRPC
  inprocess:
    name: grpc-web-bridge        # ブリッジ用 in-process サーバ
    exclusive: false             # ネイティブ gRPC と併存
grpc-web:
  path: /grpc-web
  allowed-origins: [http://localhost:5173]   # 別オリジンから呼ぶときの CORS
```

本番で frontend を同一オリジン (同じ Spring Boot や同じリバースプロキシ配下) から配信するなら CORS 設定は不要。

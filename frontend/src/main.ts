import { Code, ConnectError, createClient } from "@connectrpc/connect";
import { createGrpcWebTransport } from "@connectrpc/connect-web";
import { GreeterService } from "./gen/hello/v1/greeter_pb.ts";

// Spring Boot 側の gRPC-Web ブリッジ (grpc-web.path) を叩く
const transport = createGrpcWebTransport({ baseUrl: "/grpc-web" });
const client = createClient(GreeterService, transport);

const form = document.querySelector<HTMLFormElement>("#form")!;
const nameInput = document.querySelector<HTMLInputElement>("#name")!;
const streamButton = document.querySelector<HTMLButtonElement>("#stream")!;
const out = document.querySelector<HTMLPreElement>("#out")!;

function print(line: string, isError = false) {
  out.classList.toggle("error", isError);
  out.textContent += `${line}\n`;
}

function printError(e: unknown) {
  if (e instanceof ConnectError) {
    print(`error: ${Code[e.code]} (${e.code}): ${e.rawMessage}`, true);
  } else {
    print(`error: ${String(e)}`, true);
  }
}

form.addEventListener("submit", async (ev) => {
  ev.preventDefault();
  out.textContent = "";
  try {
    const res = await client.sayHello({ name: nameInput.value });
    print(res.message);
  } catch (e) {
    printError(e);
  }
});

streamButton.addEventListener("click", async () => {
  out.textContent = "";
  try {
    for await (const res of client.sayHelloStream({ name: nameInput.value, count: 5 })) {
      print(`#${res.index} ${res.message}`);
    }
    print("(stream completed)");
  } catch (e) {
    printError(e);
  }
});

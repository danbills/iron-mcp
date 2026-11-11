// End-to-end: MCP Inspector's CLI, a client iron-mcp did not write, against the fixture server
// (modules/fixture) over stdio, in the modern protocol era (2026-07-28). Run with scripts/it.sh, which builds the
// fixture and runs this once against the native binary and once on the JVM.
//
// FIXTURE is the server command (an executable path). Each test spawns `bunx mcp-inspector --cli`, which spawns
// the fixture, sends one request, prints the result as JSON on stdout and any error envelope on stderr, and exits:
// 0 for success, 1 for an error, 5 for a tool that returned isError (its result on stdout, the envelope on stderr).
import { describe, expect, setDefaultTimeout, test } from "bun:test";

setDefaultTimeout(60_000);

const fixture =
  process.env.FIXTURE ?? `${import.meta.dir}/../target/out/native0.5/scala-3.9.0/iron-mcp-fixture/iron-mcp-fixture`;

type Run = { exitCode: number; out: any; err: any };

function json(text: string): any {
  const line = text.trim().split("\n").filter((l) => l.startsWith("{")).pop();
  return line ? JSON.parse(line) : undefined;
}

async function inspect(...args: string[]): Promise<Run> {
  return inspectIn("modern", ...args);
}

async function inspectIn(era: "modern" | "legacy", ...args: string[]): Promise<Run> {
  const proc = Bun.spawn(
    ["bunx", "mcp-inspector", "--cli", "--protocol-era", era, "--format", "json", "-q", fixture, ...args],
    { cwd: import.meta.dir, stdout: "pipe", stderr: "pipe" },
  );
  const [out, err] = await Promise.all([new Response(proc.stdout).text(), new Response(proc.stderr).text()]);
  return { exitCode: await proc.exited, out: json(out), err: json(err) };
}

function text(result: any): string {
  return result.content.filter((c: any) => c.type === "text").map((c: any) => c.text).join("");
}

describe("the connection", () => {
  test("negotiates 2026-07-28, with the capabilities the providers imply", async () => {
    const { exitCode, out } = await inspect("--method", "initialize");
    expect(exitCode).toBe(0);
    expect(out.result.protocolVersion).toBe("2026-07-28");
    expect(out.result.serverInfo).toEqual({ name: "iron-mcp-fixture", version: "1.0.0" });
    expect(Object.keys(out.result.capabilities).sort()).toEqual(["completions", "prompts", "resources", "tools"]);
    expect(out.result.instructions).toContain("integration fixture");
  });

  test("a client speaking the legacy era is refused: iron-mcp is 2026-07-28 only", async () => {
    const { exitCode, err } = await inspectIn("legacy", "--method", "tools/list");
    expect(exitCode).toBe(1);
    expect(err.error.message).toContain("unsupported method: initialize");
  });
});

describe("tools", () => {
  test("are listed with input schemas derived from their Iron types, and output schemas from their results", async () => {
    const { exitCode, out } = await inspect("--method", "tools/list");
    expect(exitCode).toBe(0);
    const tools = Object.fromEntries(out.result.tools.map((t: any) => [t.name, t]));
    expect(Object.keys(tools).sort()).toEqual(["add", "echo", "fail", "slow"]);

    const echo = tools.echo;
    expect(echo.title).toBe("Echo");
    expect(echo.inputSchema.required).toEqual(["message"]);
    expect(echo.inputSchema.properties.message).toMatchObject({ type: "string", minLength: 1, maxLength: 200, description: "What to echo back" });
    expect(echo.inputSchema.properties.times).toMatchObject({ type: "integer", minimum: 1, maximum: 5 });
    expect(echo.outputSchema).toMatchObject({ type: "object", required: ["message", "times", "echo"] });
    expect(echo.annotations).toEqual({ readOnlyHint: true, idempotentHint: true, openWorldHint: false });

    expect(tools.add.outputSchema.properties.sum.type).toBe("number");
    expect(tools.fail.outputSchema).toBeUndefined(); // a String result declares no output schema
  });

  test("their schemas pass Inspector's portability check (--strict)", async () => {
    const { exitCode } = await inspect("--method", "tools/list", "--strict");
    expect(exitCode).toBe(0);
  });

  test("a call returns structured content matching the output schema, and the Show rendering as text", async () => {
    const { exitCode, out } = await inspect("--method", "tools/call", "--tool-name", "echo", "--tool-arg", "message=hi", "times=3");
    expect(exitCode).toBe(0);
    expect(out.result.isError).toBe(false);
    expect(out.result.structuredContent).toEqual({ message: "hi", times: 3, echo: "hi hi hi" });
    expect(text(out.result)).toBe("hi hi hi");
  });

  test("arguments can be sent as one JSON object", async () => {
    const { exitCode, out } = await inspect("--method", "tools/call", "--tool-name", "add", "--tool-args-json", '{"a":1.5,"b":2}');
    expect(exitCode).toBe(0);
    expect(out.result.structuredContent).toEqual({ sum: 3.5 });
  });

  test("an optional argument left out takes its default", async () => {
    const { out } = await inspect("--method", "tools/call", "--tool-name", "echo", "--tool-arg", "message=once");
    expect(out.result.structuredContent).toEqual({ message: "once", times: 1, echo: "once" });
  });

  test("a handler that fails is an isError result naming the tool, never a protocol error", async () => {
    const { exitCode, out, err } = await inspect("--method", "tools/call", "--tool-name", "fail");
    expect(exitCode).toBe(5);
    expect(out.result.isError).toBe(true);
    expect(text(out.result)).toBe("fail failed: this tool always fails");
    expect(err.error.code).toBe("tool_is_error");
  });

  test("an argument that breaks an Iron constraint is an isError result with the constraint's description", async () => {
    const { exitCode, out } = await inspect("--method", "tools/call", "--tool-name", "echo", "--tool-args-json", '{"message":"x","times":9}');
    expect(exitCode).toBe(5);
    expect(text(out.result)).toBe("How many times to repeat it (1 to 5)");
  });

  test("a missing required argument is an isError result too", async () => {
    const { exitCode, out } = await inspect("--method", "tools/call", "--tool-name", "add", "--tool-args-json", '{"a":1}');
    expect(exitCode).toBe(5);
    expect(out.result.isError).toBe(true);
  });

  test("a tool that reports progress still answers", async () => {
    const { exitCode, out } = await inspect("--method", "tools/call", "--tool-name", "slow", "--tool-arg", "steps=3");
    expect(exitCode).toBe(0);
    expect(text(out.result)).toBe("done after 3 steps");
  });

  test("an unknown tool fails the call", async () => {
    const { exitCode, err } = await inspect("--method", "tools/call", "--tool-name", "nope");
    expect(exitCode).not.toBe(0);
    expect(err.error.code).toBe("tool_not_found");
  });

  test("the stateless server holds up under concurrent calls (--load)", async () => {
    const { exitCode, out } = await inspect(
      "--method", "tools/call", "--tool-name", "echo", "--tool-arg", "message=load",
      "--load", "--load-requests", "200", "--load-concurrency", "20", "--load-max-error-rate", "0",
    );
    expect(exitCode).toBe(0);
    expect(out.result).toMatchObject({ requests: 200, ok: 200, toolErrors: 0, errors: 0 });
  });
});

describe("resources", () => {
  test("are listed with their media types", async () => {
    const { exitCode, out } = await inspect("--method", "resources/list");
    expect(exitCode).toBe(0);
    expect(out.result.resources.map((r: any) => [r.uri, r.mimeType])).toEqual([
      ["fixture://readme", "text/markdown"],
      ["fixture://data.json", "application/json"],
    ]);
    expect(out.result.resources[0].title).toBe("Read me");
  });

  test("a static resource is read with its media type", async () => {
    const { exitCode, out } = await inspect("--method", "resources/read", "--uri", "fixture://data.json");
    expect(exitCode).toBe(0);
    expect(out.result.contents).toEqual([{ uri: "fixture://data.json", mimeType: "application/json", text: '{"answer":42}' }]);
  });

  test("a read carries the 2026-07-28 cache hints", async () => {
    const { out } = await inspect("--method", "resources/read", "--uri", "fixture://readme");
    expect(out.result).toMatchObject({ ttlMs: 60000, cacheScope: "public" });
    expect(out.result.contents[0].text).toStartWith("# iron-mcp fixture");
  });

  test("templates are listed, and a URI they describe is read", async () => {
    const listed = await inspect("--method", "resources/templates/list");
    expect(listed.out.result.resourceTemplates).toEqual([
      { name: "item", uriTemplate: "fixture://item/{id}", description: "One item, by id", mimeType: "text/plain" },
    ]);
    const read = await inspect("--method", "resources/read", "--uri", "fixture://item/7");
    expect(read.out.result.contents[0].text).toBe("item 7");
  });

  test("an unknown URI is the server's -32002, resource not found", async () => {
    const { exitCode, err } = await inspect("--method", "resources/read", "--uri", "fixture://nope");
    expect(exitCode).toBe(1);
    expect(err.error.message).toBe("resource not found");
  });
});

describe("prompts", () => {
  test("are listed with their arguments", async () => {
    const { exitCode, out } = await inspect("--method", "prompts/list");
    expect(exitCode).toBe(0);
    expect(out.result.prompts).toEqual([
      {
        name: "greeting",
        description: "Greets someone",
        arguments: [
          { name: "name", description: "Who to greet", required: true },
          { name: "style", description: "formal or casual", required: false },
        ],
      },
    ]);
  });

  test("get fills the template from the arguments", async () => {
    const formal = await inspect("--method", "prompts/get", "--prompt-name", "greeting", "--prompt-args", "name=Ada", "style=formal");
    expect(formal.out.result).toEqual({ description: "A greeting", messages: [{ role: "user", content: { type: "text", text: "Good day, Ada." } }] });
    const casual = await inspect("--method", "prompts/get", "--prompt-name", "greeting", "--prompt-args", "name=Grace");
    expect(casual.out.result.messages[0].content.text).toBe("Hi Grace!");
  });

  test("a missing required argument is the server's -32602, passed on", async () => {
    const { exitCode, err } = await inspect("--method", "prompts/get", "--prompt-name", "greeting");
    expect(exitCode).toBe(1);
    expect(err.error.message).toBe("greeting needs a name");
  });
});

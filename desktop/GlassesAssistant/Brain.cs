using System.Net.Http;
using System.Text;
using System.Text.Json.Nodes;

namespace GlassesAssistant;

/// <summary>Claude Messages API with tools and a rolling conversation, plus one-line calls for Argue mode.</summary>
public sealed class Brain
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(90) };
    private const string ApiUrl = "https://api.anthropic.com/v1/messages";
    private const int MaxToolRounds = 6;
    private const int MaxMessages = 30;

    private readonly Settings settings;
    private readonly Tools tools;
    private readonly List<JsonObject> history = new();
    private readonly SemaphoreSlim gate = new(1, 1);
    private string? historySkin;

    public Brain(Settings settings, Tools tools)
    {
        this.settings = settings;
        this.tools = tools;
    }

    public void Reset() => history.Clear();

    public async Task<string> AskAsync(string userText, CancellationToken ct)
    {
        await gate.WaitAsync(ct);
        try
        {
            RequireKey();
            // A new character starts a fresh conversation.
            if (historySkin != settings.SkinId) { history.Clear(); historySkin = settings.SkinId; }
            StripOldImages();
            Trim();
            int rollback = history.Count;
            history.Add(new JsonObject { ["role"] = "user", ["content"] = userText });

            try
            {
                for (int round = 0; round < MaxToolRounds; round++)
                {
                    var response = await PostAsync(new JsonObject
                    {
                        ["model"] = settings.Model,
                        ["max_tokens"] = 1024,
                        ["system"] = SystemPrompt(),
                        ["tools"] = tools.Definitions(),
                        ["messages"] = Messages(),
                    }, ct);

                    var content = response["content"]!.AsArray();
                    history.Add(new JsonObject { ["role"] = "assistant", ["content"] = content.DeepClone() });

                    if ((string?)response["stop_reason"] != "tool_use")
                    {
                        var text = TextOf(content);
                        return text.Length > 0 ? text : "Done.";
                    }

                    var results = new JsonArray();
                    foreach (var block in content)
                    {
                        if ((string?)block?["type"] != "tool_use") continue;
                        var input = block!["input"] as JsonObject ?? new JsonObject();
                        var result = await tools.RunAsync((string)block["name"]!, input);
                        results.Add(ToolResultBlock((string)block["id"]!, result));
                    }
                    history.Add(new JsonObject { ["role"] = "user", ["content"] = results });
                }
                return "Sorry, that took too many steps.";
            }
            catch
            {
                // Keep the history valid (no dangling tool calls) if a request failed mid-turn.
                history.RemoveRange(rollback, history.Count - rollback);
                throw;
            }
        }
        finally
        {
            gate.Release();
        }
    }

    /// <summary>Argue mode: the next spoken line for <paramref name="speaker"/>.</summary>
    public async Task<string> DebateLineAsync(Skin speaker, IReadOnlyList<(string Who, string Text)> transcript, CancellationToken ct)
    {
        RequireKey();
        var others = string.Join(" and ", Skins.Cast.Where(s => s.Id != speaker.Id).Select(s => s.Character));
        var system = speaker.Persona + "\n\n" + DebateRules.Replace("{me}", speaker.Character).Replace("{others}", others);
        var lines = string.Join("\n", transcript.TakeLast(16).Select(l => $"{l.Who}: {l.Text}"));
        var prompt = $"The conversation so far:\n{lines}\n\nSay {speaker.Character}'s next line now. " +
                     "Only the spoken words, no name label, no stage directions.";

        var response = await PostAsync(new JsonObject
        {
            ["model"] = settings.Model,
            ["max_tokens"] = 250,
            ["system"] = system,
            ["messages"] = new JsonArray(new JsonObject { ["role"] = "user", ["content"] = prompt }),
        }, ct);

        var text = TextOf(response["content"]!.AsArray());
        var label = speaker.Character + ":";
        if (text.StartsWith(label, StringComparison.OrdinalIgnoreCase)) text = text[label.Length..].Trim();
        return text.Length > 0 ? text : "…";
    }

    private void RequireKey()
    {
        if (string.IsNullOrWhiteSpace(settings.ApiKey))
            throw new InvalidOperationException("Add your Claude API key under Settings first.");
    }

    private string SystemPrompt()
    {
        var persona = Skins.ById(settings.SkinId).Persona;
        return string.IsNullOrWhiteSpace(persona) ? BasePrompt : persona + "\n\n" + BasePrompt;
    }

    private JsonArray Messages() => new(history.Select(h => h.DeepClone()).ToArray());

    private async Task<JsonObject> PostAsync(JsonObject body, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, ApiUrl);
        request.Headers.Add("x-api-key", settings.ApiKey);
        request.Headers.Add("anthropic-version", "2023-06-01");
        request.Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json");

        using var response = await Http.SendAsync(request, ct);
        var text = await response.Content.ReadAsStringAsync(ct);
        if (!response.IsSuccessStatusCode)
            throw new HttpRequestException($"API {(int)response.StatusCode}: {(text.Length > 300 ? text[..300] : text)}");
        return JsonNode.Parse(text)!.AsObject();
    }

    private static JsonObject ToolResultBlock(string id, ToolResult result)
    {
        var block = new JsonObject { ["type"] = "tool_result", ["tool_use_id"] = id };
        if (result.Jpeg == null)
        {
            block["content"] = result.Text;
        }
        else
        {
            block["content"] = new JsonArray(
                new JsonObject
                {
                    ["type"] = "image",
                    ["source"] = new JsonObject
                    {
                        ["type"] = "base64",
                        ["media_type"] = "image/jpeg",
                        ["data"] = Convert.ToBase64String(result.Jpeg),
                    },
                },
                new JsonObject { ["type"] = "text", ["text"] = result.Text });
        }
        return block;
    }

    private static string TextOf(JsonArray content)
    {
        var sb = new StringBuilder();
        foreach (var block in content)
            if ((string?)block?["type"] == "text") sb.Append((string?)block["text"]);
        return sb.ToString().Trim();
    }

    /// <summary>Screenshots from earlier turns are expensive to resend; replace them with a note.</summary>
    private void StripOldImages()
    {
        foreach (var msg in history)
        {
            if (msg["content"] is not JsonArray content) continue;
            foreach (var block in content)
            {
                if (block?["content"] is not JsonArray inner) continue;
                for (int j = 0; j < inner.Count; j++)
                    if ((string?)inner[j]?["type"] == "image")
                        inner[j] = new JsonObject { ["type"] = "text", ["text"] = "[earlier screenshot removed]" };
            }
        }
    }

    /// <summary>Keeps the conversation short, always starting at a plain user message.</summary>
    private void Trim()
    {
        while (history.Count > MaxMessages) history.RemoveAt(0);
        while (history.Count > 0 && !((string?)history[0]["role"] == "user" && history[0]["content"] is JsonValue))
            history.RemoveAt(0);
    }

    private const string BasePrompt = """
        You run on the user's Windows PC as their desktop assistant. Your replies are spoken aloud, so keep them short:
        usually one to three sentences, no markdown, no lists, no emoji. Stay in your character's voice the whole time,
        but get the actual answer across. You can see the user's screen with look_at_screen; use it whenever they refer
        to something on their screen. Use show_on_screen for anything easier to read than hear, such as numbers or codes.
        If a tool fails, say so briefly and carry on.
        """;

    private const string DebateRules = """
        You are {me}, in a live, spoken, three-way argument with {others}, all of you living inside the user's computer.
        The user may jump in; their lines are labeled "User". Stay fully in character as {me}.
        Actually argue: take a clear position, disagree, push back on the last thing said, needle the others by name,
        mock their weak points, defend your own view, and occasionally gang up or form a grudging alliance.
        Each turn is spoken aloud: one to three short sentences, no markdown, no emoji, no stage directions.
        Never just agree and wrap things up; keep the argument alive.
        """;
}

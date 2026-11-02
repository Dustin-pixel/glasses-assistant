using System.Runtime.InteropServices;
using System.Text.Json.Nodes;

namespace GlassesAssistant;

public sealed record ToolResult(string Text, byte[]? Jpeg = null);

/// <summary>Things the assistant can do on the PC. Add abilities by adding a definition and a case.</summary>
public sealed class Tools
{
    private readonly Action<string, int> showOnScreen;
    private readonly Action<string> timerDone;

    public Tools(Action<string, int> showOnScreen, Action<string> timerDone)
    {
        this.showOnScreen = showOnScreen;
        this.timerDone = timerDone;
    }

    public JsonArray Definitions() => new(
        Def("get_time", "Get the current local date, time and time zone.", new JsonObject()),
        Def("set_timer", "Start a countdown timer. The user is alerted by voice and on screen when it ends.",
            new JsonObject
            {
                ["seconds"] = Prop("integer", "Duration in seconds"),
                ["label"] = Prop("string", "Short name, e.g. 'pasta'"),
            }, "seconds"),
        Def("show_on_screen", "Show short text in big letters on the assistant's display panel. Use for numbers, codes, " +
            "directions or anything easier to read than hear. Keep it under ~80 characters.",
            new JsonObject
            {
                ["text"] = Prop("string", "Text to show"),
                ["seconds"] = Prop("integer", "How long to show it (default 10)"),
            }, "text"),
        Def("look_at_screen", "Take a screenshot of the user's main monitor to see what they are looking at. " +
            "Use when they refer to something on their screen (this error, this page, what am I looking at).",
            new JsonObject()));

    public async Task<ToolResult> RunAsync(string name, JsonObject input)
    {
        try
        {
            switch (name)
            {
                case "get_time":
                    return new ToolResult(DateTime.Now.ToString("dddd, MMMM d yyyy, h:mm tt") + " " + TimeZoneInfo.Local.StandardName);

                case "set_timer":
                {
                    int seconds = Math.Clamp((int)(input["seconds"]?.GetValue<double>() ?? 0), 1, 86400);
                    string label = input["label"]?.GetValue<string>() ?? "timer";
                    _ = Task.Delay(TimeSpan.FromSeconds(seconds)).ContinueWith(_ => timerDone(label));
                    return new ToolResult($"Timer '{label}' set for {seconds} seconds.");
                }

                case "show_on_screen":
                {
                    string text = input["text"]?.GetValue<string>() ?? "";
                    int seconds = Math.Clamp((int)(input["seconds"]?.GetValue<double>() ?? 10), 1, 120);
                    showOnScreen(text, seconds);
                    return new ToolResult("Shown.");
                }

                case "look_at_screen":
                    var jpeg = await Task.Run(ScreenCapture.CaptureJpeg);
                    return new ToolResult("Screenshot of the user's main monitor, just now.", jpeg);

                default:
                    return new ToolResult($"Error: unknown tool {name}");
            }
        }
        catch (Exception e)
        {
            return new ToolResult("Error: " + e.Message);
        }
    }

    private static JsonObject Def(string name, string description, JsonObject properties, params string[] required) => new()
    {
        ["name"] = name,
        ["description"] = description,
        ["input_schema"] = new JsonObject
        {
            ["type"] = "object",
            ["properties"] = properties,
            ["required"] = new JsonArray(required.Select(r => (JsonNode?)JsonValue.Create(r)).ToArray()),
        },
    };

    private static JsonObject Prop(string type, string description) => new()
    {
        ["type"] = type,
        ["description"] = description,
    };
}

internal static class ScreenCapture
{
    [DllImport("user32.dll")]
    private static extern int GetSystemMetrics(int index);

    /// <summary>Main monitor as a JPEG, scaled so the long side is at most 1568 px.</summary>
    public static byte[] CaptureJpeg()
    {
        int w = GetSystemMetrics(0), h = GetSystemMetrics(1);
        using var full = new System.Drawing.Bitmap(w, h);
        using (var g = System.Drawing.Graphics.FromImage(full))
            g.CopyFromScreen(0, 0, 0, 0, new System.Drawing.Size(w, h));

        double scale = Math.Min(1.0, 1568.0 / Math.Max(w, h));
        using var scaled = new System.Drawing.Bitmap(full, new System.Drawing.Size((int)(w * scale), (int)(h * scale)));
        using var ms = new MemoryStream();
        scaled.Save(ms, System.Drawing.Imaging.ImageFormat.Jpeg);
        return ms.ToArray();
    }
}

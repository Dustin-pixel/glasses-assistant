using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace GlassesAssistant;

/// <summary>Saved in %AppData%\GlassesAssistant\settings.json. The API key is encrypted for your Windows account.</summary>
public sealed class Settings
{
    public string ApiKeyProtected { get; set; } = "";
    public string Model { get; set; } = "claude-haiku-5-5";
    public string SkinId { get; set; } = "default";
    public bool ArgueMode { get; set; }
    public bool SpeakReplies { get; set; } = true;

    [JsonIgnore]
    public string ApiKey
    {
        get
        {
            if (string.IsNullOrEmpty(ApiKeyProtected)) return "";
            try
            {
                var raw = ProtectedData.Unprotect(Convert.FromBase64String(ApiKeyProtected), null, DataProtectionScope.CurrentUser);
                return Encoding.UTF8.GetString(raw);
            }
            catch { return ""; }
        }
        set
        {
            ApiKeyProtected = string.IsNullOrWhiteSpace(value)
                ? ""
                : Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(value.Trim()), null, DataProtectionScope.CurrentUser));
        }
    }

    private static string Dir => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "GlassesAssistant");
    private static string FilePath => Path.Combine(Dir, "settings.json");

    public static Settings Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new Settings();
        }
        catch { /* corrupt file: start fresh */ }
        return new Settings();
    }

    public void Save()
    {
        Directory.CreateDirectory(Dir);
        File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
    }
}

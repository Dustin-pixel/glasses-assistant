using System.Collections.ObjectModel;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;

namespace GlassesAssistant;

public sealed class LogLine
{
    public string Who { get; init; } = "";
    public string Text { get; init; } = "";
    public Brush Brush { get; init; } = Brushes.White;
}

public partial class MainWindow : Window
{
    private readonly Settings settings = Settings.Load();
    private readonly Speech speech = new();
    private readonly Brain brain;
    private readonly ObservableCollection<LogLine> lines = new();
    private readonly List<(string Who, string SkinId, string Text)> debate = new();
    private readonly Random random = new();
    private readonly DispatcherTimer bigTextTimer = new();
    private CancellationTokenSource? turn;
    private bool ready;

    private const int LinesPerRound = 4;

    public MainWindow()
    {
        InitializeComponent();
        brain = new Brain(settings, new Tools(ShowBigText, TimerDone));
        Log.ItemsSource = lines;
        bigTextTimer.Tick += (_, _) => { bigTextTimer.Stop(); HudBig.Visibility = Visibility.Collapsed; };

        foreach (var skin in Skins.All)
        {
            var rb = new RadioButton { Content = skin.Label, GroupName = "skin", Tag = skin.Id, IsChecked = skin.Id == settings.SkinId };
            rb.Checked += Skin_Checked;
            SkinPanel.Children.Add(rb);
        }
        ModeAssist.IsChecked = !settings.ArgueMode;
        ModeArgue.IsChecked = settings.ArgueMode;
        KeyBox.Password = settings.ApiKey;
        ModelBox.Text = settings.Model;
        SpeakBox.IsChecked = settings.SpeakReplies;
        ready = true;

        ApplySkin();
        RefreshControls();
        if (string.IsNullOrWhiteSpace(settings.ApiKey))
        {
            SettingsExpander.IsExpanded = true;
            SetStatus("Paste your Claude API key under Settings and tap Save to get started.");
        }
    }

    private Skin CurrentSkin => Skins.ById(settings.SkinId);

    // ------------------------------------------------------------------ global hotkey (Ctrl+Alt+Space)

    private const int HotkeyId = 0x4741;
    [DllImport("user32.dll")] private static extern bool RegisterHotKey(IntPtr hWnd, int id, uint modifiers, uint vk);
    [DllImport("user32.dll")] private static extern bool UnregisterHotKey(IntPtr hWnd, int id);

    protected override void OnSourceInitialized(EventArgs e)
    {
        base.OnSourceInitialized(e);
        var handle = new WindowInteropHelper(this).Handle;
        HwndSource.FromHwnd(handle)?.AddHook(WndProc);
        const uint alt = 0x1, ctrl = 0x2, noRepeat = 0x4000, space = 0x20;
        if (!RegisterHotKey(handle, HotkeyId, alt | ctrl | noRepeat, space))
            SetStatus("Ctrl+Alt+Space is already used by another app; use the Talk button instead.");
    }

    private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == 0x0312 && wParam.ToInt32() == HotkeyId)
        {
            handled = true;
            _ = TalkAsync();
        }
        return IntPtr.Zero;
    }

    protected override void OnClosed(EventArgs e)
    {
        UnregisterHotKey(new WindowInteropHelper(this).Handle, HotkeyId);
        turn?.Cancel();
        speech.Dispose();
        base.OnClosed(e);
    }

    // ------------------------------------------------------------------ UI events

    private void Skin_Checked(object sender, RoutedEventArgs e)
    {
        if (!ready) return;
        settings.SkinId = (string)((RadioButton)sender).Tag;
        settings.Save();
        ApplySkin();
    }

    private void Mode_Checked(object sender, RoutedEventArgs e)
    {
        if (!ready) return;
        settings.ArgueMode = ModeArgue.IsChecked == true;
        settings.Save();
        ApplySkin();
        RefreshControls();
    }

    private void Speak_Changed(object sender, RoutedEventArgs e)
    {
        if (!ready) return;
        settings.SpeakReplies = SpeakBox.IsChecked == true;
        settings.Save();
    }

    private void Save_Click(object sender, RoutedEventArgs e)
    {
        settings.ApiKey = KeyBox.Password;
        settings.Model = string.IsNullOrWhiteSpace(ModelBox.Text) ? "claude-haiku-5-5" : ModelBox.Text.Trim();
        settings.Save();
        SetStatus("Saved.");
    }

    private async void Talk_Click(object sender, RoutedEventArgs e) => await TalkAsync();

    private async void Send_Click(object sender, RoutedEventArgs e) => await SendTypedAsync();

    private async void Input_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter) return;
        e.Handled = true;
        await SendTypedAsync();
    }

    private async void Keep_Click(object sender, RoutedEventArgs e)
    {
        if (debate.Count > 0) await RunTurnAsync(ct => RunDebateAsync(LinesPerRound, ct));
    }

    private void New_Click(object sender, RoutedEventArgs e)
    {
        CancelTurn();
        debate.Clear();
        lines.Clear();
        ShowHud("ARGUE MODE", "Give them a topic.");
        RefreshControls();
    }

    private void Stop_Click(object sender, RoutedEventArgs e) => CancelTurn();

    // ------------------------------------------------------------------ turns

    private Task TalkAsync() => RunTurnAsync(async ct =>
    {
        ShowHud(HudLabel(), "Listening…");
        SetStatus("");
        var heard = await speech.ListenAsync(ct);
        if (string.IsNullOrWhiteSpace(heard))
        {
            ShowHud(HudLabel(), "Didn't catch that.");
            return;
        }
        await HandleUserTextAsync(heard, ct);
    });

    private Task SendTypedAsync()
    {
        var text = Input.Text.Trim();
        Input.Clear();
        if (text.Length > 0) return RunTurnAsync(ct => HandleUserTextAsync(text, ct));
        if (settings.ArgueMode && debate.Count > 0) return RunTurnAsync(ct => RunDebateAsync(LinesPerRound, ct));
        return Task.CompletedTask;
    }

    private async Task RunTurnAsync(Func<CancellationToken, Task> work)
    {
        CancelTurn();
        var cts = new CancellationTokenSource();
        turn = cts;
        RefreshControls();
        try
        {
            await work(cts.Token);
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            SetStatus("Error: " + ex.Message);
            ShowHud(HudLabel(), "Something went wrong.");
        }
        finally
        {
            if (turn == cts) turn = null;
            cts.Dispose();
            RefreshControls();
        }
    }

    private void CancelTurn()
    {
        try { turn?.Cancel(); } catch (ObjectDisposedException) { }
        speech.StopSpeaking();
    }

    private async Task HandleUserTextAsync(string text, CancellationToken ct)
    {
        AddLine("You", text, null);
        if (settings.ArgueMode)
        {
            debate.Add(("User", "user", text));
            await RunDebateAsync(LinesPerRound, ct);
            return;
        }

        var skin = CurrentSkin;
        ShowHud(HudLabel(), skin.Thinking);
        var reply = await brain.AskAsync(text, ct);
        AddLine(skin.Id == "default" ? "Assistant" : skin.Character, reply, skin);
        ShowHud(HudLabel(), reply);
        await SayAsync(reply, skin, ct);
    }

    private async Task RunDebateAsync(int count, CancellationToken ct)
    {
        for (int i = 0; i < count; i++)
        {
            ct.ThrowIfCancellationRequested();
            var speaker = NextSpeaker();
            ApplyTheme(speaker);
            ShowHud(speaker.Character.ToUpperInvariant(), speaker.Thinking);
            var transcript = debate.Select(d => (d.Who, d.Text)).ToList();
            var line = await brain.DebateLineAsync(speaker, transcript, ct);
            debate.Add((speaker.Character, speaker.Id, line));
            AddLine(speaker.Character, line, speaker);
            ShowHud(speaker.Character.ToUpperInvariant(), line);
            await SayAsync(line, speaker, ct);
        }
        RefreshControls();
    }

    /// <summary>Whoever was just called out by name answers; otherwise anyone but the last speaker.</summary>
    private Skin NextSpeaker()
    {
        var last = debate.Count > 0 ? debate[^1] : default;
        var options = Skins.Cast.Where(s => s.Id != last.SkinId).ToList();
        var named = options.FirstOrDefault(s =>
            last.Text != null && last.Text.Contains(s.Character.Split(' ')[^1], StringComparison.OrdinalIgnoreCase));
        return named ?? options[random.Next(options.Count)];
    }

    private async Task SayAsync(string text, Skin skin, CancellationToken ct)
    {
        if (settings.SpeakReplies) await speech.SpeakAsync(text, skin, ct);
    }

    // ------------------------------------------------------------------ tool callbacks (may arrive off the UI thread)

    private void ShowBigText(string text, int seconds) => Dispatcher.Invoke(() =>
    {
        HudBig.Text = text;
        HudBig.Visibility = Visibility.Visible;
        bigTextTimer.Stop();
        bigTextTimer.Interval = TimeSpan.FromSeconds(seconds);
        bigTextTimer.Start();
    });

    private void TimerDone(string label) => Dispatcher.Invoke(() =>
    {
        var skin = CurrentSkin;
        var message = skin.TimerDone(label);
        AddLine(skin.Id == "default" ? "Assistant" : skin.Character, message, skin);
        ShowBigText("⏰ " + label, 15);
        if (settings.SpeakReplies) _ = speech.SpeakAsync(message, skin, CancellationToken.None);
    });

    // ------------------------------------------------------------------ display helpers

    private string HudLabel() => settings.ArgueMode ? "ARGUE MODE" : CurrentSkin.Label.ToUpperInvariant();

    private void ShowHud(string title, string text)
    {
        HudTitle.Text = title;
        HudText.Text = text;
    }

    private void AddLine(string who, string text, Skin? skin)
    {
        var brush = skin == null ? (Brush)FindResource("TextBrush") : new SolidColorBrush(Hex(skin.Accent));
        lines.Add(new LogLine { Who = who, Text = text, Brush = brush });
        LogScroll.ScrollToEnd();
    }

    private void SetStatus(string text) => StatusText.Text = text;

    private void ApplySkin()
    {
        var skin = CurrentSkin;
        ApplyTheme(skin);
        SkinPanel.Visibility = settings.ArgueMode ? Visibility.Collapsed : Visibility.Visible;
        ShowHud(HudLabel(), settings.ArgueMode
            ? (debate.Count == 0 ? "Give them a topic: say it, or type it below." : "Your move.")
            : skin.Ready);
    }

    private void ApplyTheme(Skin skin)
    {
        Resources["BgBrush"] = new SolidColorBrush(Hex(skin.Background));
        Resources["SurfaceBrush"] = new SolidColorBrush(Hex(skin.Surface));
        Resources["TextBrush"] = new SolidColorBrush(Hex(skin.Text));
        Resources["AccentBrush"] = new SolidColorBrush(Hex(skin.Accent));
    }

    private void RefreshControls()
    {
        bool argue = settings.ArgueMode;
        bool busy = turn != null;
        TalkButton.Content = !argue ? "🎤 Talk" : debate.Count == 0 ? "🎤 Give a topic" : "🎤 Butt in";
        KeepButton.Visibility = argue ? Visibility.Visible : Visibility.Collapsed;
        NewButton.Visibility = argue ? Visibility.Visible : Visibility.Collapsed;
        KeepButton.IsEnabled = argue && debate.Count > 0 && !busy;
        StopButton.IsEnabled = busy;
    }

    private static Color Hex(string hex) => (Color)ColorConverter.ConvertFromString(hex);
}

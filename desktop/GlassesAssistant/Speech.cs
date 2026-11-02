using System.IO;
using System.Buffers.Binary;
using System.Runtime.InteropServices;
using NAudio.Wave;
using Windows.Media.SpeechRecognition;
using Windows.Media.SpeechSynthesis;

namespace GlassesAssistant;

/// <summary>Windows speech recognition in, Windows voices out (with each skin's effect).</summary>
public sealed class Speech : IDisposable
{
    private SpeechRecognizer? recognizer;
    private readonly SpeechSynthesizer synth = new();
    private WaveOutEvent? output;

    /// <summary>Listens for one spoken phrase. Returns null on silence.</summary>
    public async Task<string?> ListenAsync(CancellationToken ct)
    {
        try
        {
            if (recognizer == null)
            {
                var r = new SpeechRecognizer();
                r.Constraints.Add(new SpeechRecognitionTopicConstraint(SpeechRecognitionScenario.Dictation, "dictation"));
                r.Timeouts.InitialSilenceTimeout = TimeSpan.FromSeconds(6);
                r.Timeouts.EndSilenceTimeout = TimeSpan.FromSeconds(1.2);
                var compiled = await r.CompileConstraintsAsync();
                if (compiled.Status != SpeechRecognitionResultStatus.Success)
                    throw new InvalidOperationException($"Speech recognition isn't available ({compiled.Status}).");
                recognizer = r;
            }

            var op = recognizer.RecognizeAsync();
            using (ct.Register(() => { try { op.Cancel(); } catch { } }))
            {
                var result = await op;
                return result.Status == SpeechRecognitionResultStatus.Success && !string.IsNullOrWhiteSpace(result.Text)
                    ? result.Text
                    : null;
            }
        }
        catch (COMException e) when ((uint)e.HResult == 0x80045509)
        {
            throw new InvalidOperationException(
                "Windows speech recognition is turned off. Open Settings > Privacy & security > Speech and turn on Online speech recognition.");
        }
        catch (UnauthorizedAccessException)
        {
            throw new InvalidOperationException(
                "Microphone access is blocked. Open Settings > Privacy & security > Microphone and allow desktop apps.");
        }
    }

    /// <summary>Speaks in the skin's voice with its effect, and waits until it finishes.</summary>
    public async Task SpeakAsync(string text, Skin skin, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(text)) return;
        var voice = PickVoice(skin.Voice);
        if (voice != null) synth.Voice = voice;
        synth.Options.AudioPitch = skin.Pitch;
        synth.Options.SpeakingRate = skin.Rate;

        byte[] wav;
        using (var stream = await synth.SynthesizeTextToStreamAsync(text))
        using (var s = stream.AsStreamForRead())
        using (var ms = new MemoryStream())
        {
            await s.CopyToAsync(ms, ct);
            wav = ms.ToArray();
        }
        var pcm = VoiceFx.ReadWav(wav);
        if (pcm == null || pcm.Samples.Length == 0) return;
        var processed = await Task.Run(() => VoiceFx.Apply(skin.Fx, pcm.Samples, pcm.Rate), ct);
        await PlayAsync(processed, pcm.Rate, ct);
    }

    private static VoiceInformation? PickVoice(VoiceGender gender)
    {
        if (gender == VoiceGender.Any) return SpeechSynthesizer.DefaultVoice;
        var wanted = gender == VoiceGender.Male ? Windows.Media.SpeechSynthesis.VoiceGender.Male
                                                : Windows.Media.SpeechSynthesis.VoiceGender.Female;
        var lang = SpeechSynthesizer.DefaultVoice?.Language;
        return SpeechSynthesizer.AllVoices.FirstOrDefault(v => v.Gender == wanted && v.Language == lang)
            ?? SpeechSynthesizer.AllVoices.FirstOrDefault(v => v.Gender == wanted)
            ?? SpeechSynthesizer.DefaultVoice;
    }

    private async Task PlayAsync(float[] samples, int rate, CancellationToken ct)
    {
        var bytes = new byte[samples.Length * 2];
        for (int i = 0; i < samples.Length; i++)
        {
            short v = (short)Math.Clamp((int)(samples[i] * 32767f), short.MinValue, short.MaxValue);
            BinaryPrimitives.WriteInt16LittleEndian(bytes.AsSpan(i * 2), v);
        }

        var done = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var wo = new WaveOutEvent();
        output = wo;
        wo.PlaybackStopped += (_, _) => done.TrySetResult();
        using var provider = new RawSourceWaveStream(new MemoryStream(bytes), new WaveFormat(rate, 16, 1));
        wo.Init(provider);
        wo.Play();
        try
        {
            using (ct.Register(() => { try { wo.Stop(); } catch { } }))
                await done.Task;
        }
        finally
        {
            if (output == wo) output = null;
            wo.Dispose();
        }
        ct.ThrowIfCancellationRequested();
    }

    public void StopSpeaking()
    {
        try { output?.Stop(); } catch { }
    }

    public void Dispose()
    {
        StopSpeaking();
        recognizer?.Dispose();
        synth.Dispose();
    }
}

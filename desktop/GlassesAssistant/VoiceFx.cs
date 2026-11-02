using System.Buffers.Binary;
using System.Text;

namespace GlassesAssistant;

public sealed record Pcm(float[] Samples, int Rate);

/// <summary>Offline effects chain for synthesized speech (same treatments as the phone app).</summary>
public static class VoiceFx
{
    public static float[] Apply(Fx fx, float[] x, int sr)
    {
        var y = fx switch
        {
            Fx.Metal => Metal(x, sr),
            Fx.Synthetic => Synthetic(x, sr),
            Fx.Glitch => Glitch(x, sr),
            _ => (float[])x.Clone(),
        };
        Normalize(y, 0.92f);
        return y;
    }

    /// <summary>Ultron: doubled voice with a wobbling delay, a buzzing metallic modulation and grit.</summary>
    private static float[] Metal(float[] x, int sr)
    {
        var o = new float[x.Length];
        double baseD = 0.017 * sr, depth = 0.004 * sr, drive = 1.7, norm = Math.Tanh(drive);
        for (int n = 0; n < x.Length; n++)
        {
            double lfo = Math.Sin(2 * Math.PI * 0.33 * n / sr);
            int d = (int)(baseD + depth * lfo);
            float doubled = n - d >= 0 ? x[n - d] : 0f;
            double y = 0.8 * x[n] + 0.45 * doubled;
            y *= 0.68 + 0.32 * Math.Sin(2 * Math.PI * 46.0 * n / sr);
            o[n] = (float)(Math.Tanh(y * drive) / norm);
        }
        return o;
    }

    /// <summary>Great Sage: thin and even, a short robotic resonance and a faint echo.</summary>
    private static float[] Synthetic(float[] x, int sr)
    {
        var hp = new float[x.Length];
        double rc = 1.0 / (2 * Math.PI * 200.0);
        float a = (float)(rc / (rc + 1.0 / sr));
        for (int n = 1; n < x.Length; n++) hp[n] = a * (hp[n - 1] + x[n] - x[n - 1]);

        var comb = new float[hp.Length];
        int cd = Math.Max(1, (int)(0.006 * sr));
        for (int n = 0; n < hp.Length; n++) comb[n] = hp[n] + (n >= cd ? 0.42f * comb[n - cd] : 0f);

        int ed = (int)(0.17 * sr);
        var o = new float[comb.Length + 2 * ed];
        for (int n = 0; n < o.Length; n++)
        {
            float dry = n < comb.Length ? comb[n] : 0f;
            float e1 = n - ed >= 0 && n - ed < comb.Length ? comb[n - ed] : 0f;
            float e2 = n - 2 * ed >= 0 && n - 2 * ed < comb.Length ? comb[n - 2 * ed] : 0f;
            o[n] = dry + 0.22f * e1 + 0.08f * e2;
        }
        return o;
    }

    /// <summary>Capri: light digital crunch with random stutters, dropouts and crushed bursts.</summary>
    private static float[] Glitch(float[] x, int sr)
    {
        var rnd = new Random(x.Length);
        var o = new List<float>(x.Length + x.Length / 3);
        int n = 0;
        int next = (int)(sr * (0.5 + rnd.NextDouble() * 0.6));
        while (n < x.Length)
        {
            if (n >= next && n + sr / 8 < x.Length)
            {
                int len = (int)(sr * (0.06 + rnd.NextDouble() * 0.06));
                switch (rnd.Next(3))
                {
                    case 0:
                        int reps = 2 + rnd.Next(2);
                        for (int r = 0; r < reps; r++) for (int i = 0; i < len; i++) o.Add(x[n + i]);
                        break;
                    case 1:
                        for (int i = 0; i < (int)(sr * 0.035); i++) o.Add(0f);
                        break;
                    default:
                        for (int i = 0; i < len; i++) o.Add(Crush(x[n + i - (i % 4)], 4));
                        break;
                }
                n += len;
                next = n + (int)(sr * (0.7 + rnd.NextDouble() * 0.8));
            }
            else
            {
                o.Add(0.85f * x[n] + 0.15f * Crush(x[n], 6));
                n++;
            }
        }
        return o.ToArray();
    }

    private static float Crush(float v, int bits)
    {
        float steps = 1 << (bits - 1);
        return MathF.Round(v * steps) / steps;
    }

    private static void Normalize(float[] y, float target)
    {
        float peak = 0f;
        foreach (var v in y) peak = Math.Max(peak, Math.Abs(v));
        if (peak < 1e-4f) return;
        float g = target / peak;
        for (int i = 0; i < y.Length; i++) y[i] *= g;
    }

    /// <summary>Reads a 16-bit PCM WAV into mono floats (first channel).</summary>
    public static Pcm? ReadWav(byte[] b)
    {
        if (b.Length < 44 || Encoding.ASCII.GetString(b, 0, 4) != "RIFF") return null;
        int pos = 12, format = 0, channels = 1, rate = 0, bits = 0;
        while (pos + 8 <= b.Length)
        {
            string id = Encoding.ASCII.GetString(b, pos, 4);
            int size = BinaryPrimitives.ReadInt32LittleEndian(b.AsSpan(pos + 4));
            int body = pos + 8;
            if (id == "fmt ")
            {
                format = BinaryPrimitives.ReadInt16LittleEndian(b.AsSpan(body));
                channels = Math.Max(1, (int)BinaryPrimitives.ReadInt16LittleEndian(b.AsSpan(body + 2)));
                rate = BinaryPrimitives.ReadInt32LittleEndian(b.AsSpan(body + 4));
                bits = BinaryPrimitives.ReadInt16LittleEndian(b.AsSpan(body + 14));
            }
            else if (id == "data")
            {
                if (format != 1 || bits != 16 || rate <= 0) return null;
                int end = size <= 0 || body + size > b.Length ? b.Length : body + size;
                int frames = (end - body) / (2 * channels);
                var o = new float[frames];
                for (int i = 0; i < frames; i++)
                    o[i] = BinaryPrimitives.ReadInt16LittleEndian(b.AsSpan(body + i * 2 * channels)) / 32768f;
                return new Pcm(o, rate);
            }
            if (size < 0) return null;
            pos = body + size + (size & 1);
        }
        return null;
    }
}

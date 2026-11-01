import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次性探针（不进应用构建）：把任意 id\\t文本 的 TSV 编码成 id\\t向量。
 * 供 make_synonyms.py 构造的同义基准集使用——那些文本不是"笔记里的概念"，
 * 用不了 EmbedProbe 的固定三列格式。
 *
 * 运行（仓库根目录）：
 *   java -cp "<onnxruntime>;<tokenizers>;<djl-api>;<slf4j-api>" tools/EmbedTexts.java \
 *        models/embedding/Multilingual-E5-small/model.onnx \
 *        models/embedding/Multilingual-E5-small/tokenizer.json \
 *        target/syn-texts.tsv target/syn-vec.tsv
 */
public class EmbedTexts {

    public static void main(String[] args) throws Exception {
        Path modelPath = Path.of(args[0]);
        Path tokPath = Path.of(args[1]);
        Path in = Path.of(args[2]);
        Path out = Path.of(args[3]);

        List<String> lines = Files.readAllLines(in, StandardCharsets.UTF_8);
        HuggingFaceTokenizer tok = HuggingFaceTokenizer.newInstance(tokPath);
        OrtEnvironment env = OrtEnvironment.getEnvironment();

        StringBuilder b = new StringBuilder();
        long t0 = System.currentTimeMillis();
        int done = 0;
        try (OrtSession s = env.createSession(modelPath.toString(), new OrtSession.SessionOptions())) {
            for (String line : lines) {
                int tab = line.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                String id = line.substring(0, tab);
                String text = line.substring(tab + 1).trim();
                if (text.isEmpty()) {
                    continue;
                }
                double[] v = embed(s, tok, env, text);
                b.append(id).append('\t');
                for (int i = 0; i < v.length; i++) {
                    if (i > 0) {
                        b.append(',');
                    }
                    b.append(v[i]);
                }
                b.append('\n');
                done++;
            }
        }
        Files.writeString(out, b.toString(), StandardCharsets.UTF_8);
        System.out.println("编码 " + done + " 条，耗时 " + (System.currentTimeMillis() - t0) + "ms -> "
                + out.toAbsolutePath());
    }

    private static double[] embed(OrtSession s, HuggingFaceTokenizer tok, OrtEnvironment env, String text)
            throws Exception {
        Encoding e = tok.encode("query: " + text);
        long[] ids = e.getIds();
        long[] mask = e.getAttentionMask();
        Map<String, OnnxTensor> inBody = new HashMap<>();
        try (OnnxTensor ti = OnnxTensor.createTensor(env, new long[][]{ids});
             OnnxTensor tm = OnnxTensor.createTensor(env, new long[][]{mask})) {
            inBody.put("input_ids", ti);
            inBody.put("attention_mask", tm);
            if (s.getInputInfo().containsKey("token_type_ids")) {
                try (OnnxTensor tt = OnnxTensor.createTensor(env, new long[][]{new long[ids.length]})) {
                    inBody.put("token_type_ids", tt);
                    return run(s, inBody, mask);
                }
            }
            return run(s, inBody, mask);
        }
    }

    private static double[] run(OrtSession s, Map<String, OnnxTensor> in, long[] mask) throws Exception {
        try (OrtSession.Result res = s.run(in)) {
            Object v = res.get(0).getValue();
            double[] vec;
            if (v instanceof float[][][]) {
                float[][][] o = (float[][][]) v;
                int hidden = o[0][0].length;
                vec = new double[hidden];
                double cnt = 0;
                for (int t = 0; t < o[0].length; t++) {
                    if (mask[t] == 0) {
                        continue;
                    }
                    cnt++;
                    for (int h = 0; h < hidden; h++) {
                        vec[h] += o[0][t][h];
                    }
                }
                for (int h = 0; h < hidden; h++) {
                    vec[h] /= Math.max(cnt, 1);
                }
            } else if (v instanceof float[][]) {
                float[][] o = (float[][]) v;
                vec = new double[o[0].length];
                for (int h = 0; h < vec.length; h++) {
                    vec[h] = o[0][h];
                }
            } else {
                throw new IllegalStateException("未预期的输出类型：" + v.getClass());
            }
            double n = 0;
            for (double x : vec) {
                n += x * x;
            }
            n = Math.sqrt(n);
            for (int i = 0; i < vec.length; i++) {
                vec[i] /= (n == 0 ? 1 : n);
            }
            return vec;
        }
    }
}

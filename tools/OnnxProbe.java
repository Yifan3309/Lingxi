import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class OnnxProbe {
    public static void main(String[] args) throws Exception {
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try (OrtSession s = env.createSession(args[0], new OrtSession.SessionOptions())) {
            System.out.println("inputs:");
            s.getInputInfo().forEach((k, v) -> System.out.println("  " + k + " : " + v.getInfo()));
            System.out.println("outputs:");
            s.getOutputInfo().forEach((k, v) -> System.out.println("  " + k + " : " + v.getInfo()));

            var tok = ai.djl.huggingface.tokenizers.HuggingFaceTokenizer.newInstance(Path.of(args[1]));
            String q = "数据库迁移";
            String[] docs = {
                    "我们团队决定采用 PostgreSQL 作为主数据库，Q1 完成迁移",
                    "今天天气晴朗适合出门散步"
            };
            var encodings = new ai.djl.huggingface.tokenizers.Encoding[docs.length];
            int maxLen = 0;
            for (int i = 0; i < docs.length; i++) {
                encodings[i] = tok.encode(q, docs[i]);
                maxLen = Math.max(maxLen, encodings[i].getIds().length);
            }
            long[][] ids = new long[docs.length][maxLen];
            long[][] mask = new long[docs.length][maxLen];
            long[][] types = new long[docs.length][maxLen];
            for (int i = 0; i < docs.length; i++) {
                long[] id = encodings[i].getIds();
                System.arraycopy(id, 0, ids[i], 0, id.length);
                for (int j = 0; j < id.length; j++) mask[i][j] = 1;
            }
            Map<String, OnnxTensor> inputs = new HashMap<>();
            try (OnnxTensor i = OnnxTensor.createTensor(env, ids);
                 OnnxTensor m = OnnxTensor.createTensor(env, mask)) {
                inputs.put("input_ids", i);
                inputs.put("attention_mask", m);
                if (s.getInputInfo().containsKey("token_type_ids")) {
                    try (OnnxTensor t = OnnxTensor.createTensor(env, types)) {
                        inputs.put("token_type_ids", t);
                        runAndPrint(s, inputs);
                    }
                } else {
                    runAndPrint(s, inputs);
                }
            }
        }
    }

    private static void runAndPrint(OrtSession s, Map<String, OnnxTensor> inputs) throws Exception {
        try (var out = s.run(inputs)) {
            for (var entry : out) {
                System.out.println("raw output [" + entry.getKey() + "] = " + entry.getValue().getValue());
            }
        }
    }
}

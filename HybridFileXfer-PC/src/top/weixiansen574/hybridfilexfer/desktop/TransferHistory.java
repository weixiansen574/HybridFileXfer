package top.weixiansen574.hybridfilexfer.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Local summaries only: no pairing codes, device IDs, or file paths. */
final class TransferHistory {
    private final Path file;
    TransferHistory() {
        file = Paths.get(System.getProperty("hfx.dataDir",
                Paths.get(System.getProperty("user.home"), ".hybridfilexfer").toString()), "history.tsv");
    }
    List<String[]> load() throws IOException {
        List<String[]> rows = new ArrayList<>();
        if (!Files.isRegularFile(file)) return rows;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] row = line.split("\\t", -1);
            if (row.length == 5) rows.add(row);
            if (rows.size() == 100) break;
        }
        return rows;
    }
    void save(List<String[]> rows) throws IOException {
        Files.createDirectories(file.getParent());
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < Math.min(rows.size(), 100); i++) {
            String[] row = rows.get(i).clone();
            for (int c = 0; c < row.length; c++) row[c] = row[c].replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
            lines.add(String.join("\t", row));
        }
        Path temporary = Files.createTempFile(file.getParent(), "history-", ".tmp");
        try {
            Files.write(temporary, lines, StandardCharsets.UTF_8);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}

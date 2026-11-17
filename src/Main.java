import javazoom.jl.player.Player;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;

public class Main {
    record Download(URI uri, Path target) {}

    public static void main(String[] args) {
        Path input = Path.of(args.length > 0 ? args[0] : "downloads.txt");
        try {
            List<Download> jobs = readJobs(input);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<?> image = executor.submit(() -> runDownload(jobs.get(0), false));
                Future<?> music = executor.submit(() -> runDownload(jobs.get(1), true));
                image.get();
                music.get();
                System.out.println("Работа программы завершена.");
            } finally {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            System.err.println("Работа прервана.");
        } catch (Exception ex) {
            System.err.println("Ошибка: " + ex.getMessage());
        }
    }

    static List<Download> readJobs(Path input) throws IOException {
        List<String> lines = Files.readAllLines(input, StandardCharsets.UTF_8);
        if (lines.size() != 2) throw new IOException("Файл задания должен содержать ровно две строки.");
        Path base = input.toAbsolutePath().normalize().getParent();
        Download image = parseLine(lines.get(0).replaceFirst("^\uFEFF", ""), base, 1);
        Download music = parseLine(lines.get(1), base, 2);
        if (image.target().equals(music.target()) ||
                (Files.exists(image.target()) && Files.exists(music.target())
                        && Files.isSameFile(image.target(), music.target())))
            throw new IOException("У изображения и MP3 должны быть разные пути сохранения.");
        return List.of(image, music);
    }

    private static Download parseLine(String line, Path base, int number) throws IOException {
        String[] parts = line.trim().split("\\s+", 2);
        if (parts.length != 2 || parts[1].isBlank())
            throw new IOException("Строка " + number + ": ожидаются ссылка и путь через пробел.");
        try {
            URI uri = URI.create(parts[0]);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) throw new IllegalArgumentException("Нужна HTTP/HTTPS-ссылка");
            String filename = parts[1].trim();
            if (filename.startsWith("\"") && filename.endsWith("\""))
                filename = filename.substring(1, filename.length() - 1);
            if (filename.isBlank()) throw new IllegalArgumentException("Пустой путь сохранения");
            Path target = base.resolve(Path.of(filename)).normalize();
            return new Download(uri, target);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Строка " + number + ": " + ex.getMessage(), ex);
        }
    }

    private static void runDownload(Download job, boolean music) {
        String name = music ? "MP3" : "Изображение";
        try {
            System.out.println(name + ": начало скачивания — " + job.uri());
            download(job);
            System.out.println(name + ": скачивание завершено — " + job.target());
        } catch (Exception ex) {
            System.err.println(name + ": ошибка скачивания — " + ex.getMessage());
            return;
        }

        if (music) {
            try (InputStream stream = new BufferedInputStream(Files.newInputStream(job.target()))) {
                Player player = new Player(stream);
                try {
                    System.out.println("MP3: воспроизведение началось.");
                    player.play();
                    System.out.println("MP3: воспроизведение завершено.");
                } finally {
                    player.close();
                }
            } catch (Exception ex) {
                System.err.println("MP3: ошибка воспроизведения — " + ex.getMessage());
            }
        }
    }

    static void download(Download job) throws IOException {
        Path parent = job.target().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "lab8-", ".part");
        HttpURLConnection connection = null;
        try {
            URI current = job.uri();
            for (int redirects = 0; ; redirects++) {
                connection = (HttpURLConnection) current.toURL().openConnection();
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(15_000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "Lab8/1.0");
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (redirects >= 5 || location == null) throw new IOException("Ошибка перенаправления сервера.");
                    current = current.resolve(location);
                    if (!("http".equalsIgnoreCase(current.getScheme()) || "https".equalsIgnoreCase(current.getScheme())))
                        throw new IOException("Недопустимый протокол перенаправления.");
                    connection.disconnect();
                } else {
                    if (code != 200) throw new IOException("Сервер вернул HTTP " + code);
                    break;
                }
            }
            String type = connection.getContentType();
            if (type != null && type.toLowerCase(java.util.Locale.ROOT).contains("text/html"))
                throw new IOException("Получена HTML-страница. Укажите прямую ссылку на файл.");
            long expected = connection.getContentLengthLong();
            long received = 0;
            try (InputStream in = connection.getInputStream(); OutputStream out = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Скачивание прервано.");
                    out.write(buffer, 0, count);
                    received += count;
                }
            }
            if (received == 0) throw new IOException("Сервер вернул пустой файл.");
            if (expected >= 0 && received != expected) throw new IOException("Файл получен не полностью.");
            Files.move(temporary, job.target(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (connection != null) connection.disconnect();
            Files.deleteIfExists(temporary);
        }
    }
}

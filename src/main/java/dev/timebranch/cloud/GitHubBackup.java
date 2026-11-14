package dev.timebranch.cloud;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.*;

/**
 * Optional real cloud backup through GitHub's Contents API.
 * A user-supplied fine-grained token is held in memory, never persisted.
 * Cloud backup is not claimed unless GitHub returns a successful response.
 */
public final class GitHubBackup {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12)).build();

    public static String backupPath() { return "timebranch-backups/workspace.tbr"; }

    private static String endpoint(String owner, String repo) {
        if (owner == null || !owner.matches("[A-Za-z0-9-]{1,39}")
                || repo == null || !repo.matches("[A-Za-z0-9._-]{1,100}"))
            throw new IllegalArgumentException("Enter a valid GitHub owner and repository.");
        return "https://api.github.com/repos/" + owner + "/" + repo + "/contents/" + backupPath();
    }
    private static String quote(String value) {
        return "\"" + value.replace("\\","\\\\").replace("\"","\\\"") + "\"";
    }
    private HttpResponse<String> request(String method, String url, String token, String json)
            throws IOException, InterruptedException {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("GitHub token is required.");
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization","Bearer " + token.trim())
                .header("Accept","application/vnd.github+json")
                .header("X-GitHub-Api-Version","2022-11-28");
        if ("PUT".equals(method)) {
            b.header("Content-Type", "application/json");
            b.PUT(HttpRequest.BodyPublishers.ofString(json));
        } else b.GET();
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String extract(String json, String key) throws IOException {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"")
                .matcher(json);
        if (!m.find()) throw new IOException("GitHub returned no " + key + " field.");
        return m.group(1).replace("\\n", "").replace("\\r", "").replace("\\/", "/");
    }
    private static void expectSuccess(HttpResponse<String> r, int... expected) throws IOException {
        for (int ok : expected) if (r.statusCode() == ok) return;
        String explanation = switch (r.statusCode()) {
            case 401 -> "GitHub token is invalid.";
            case 403 -> "GitHub denied access; token needs Contents read/write permission.";
            case 404 -> "Repository not found or token cannot access this private repository.";
            case 409 -> "GitHub backup conflict. Retry after checking the remote file.";
            default -> "GitHub HTTP " + r.statusCode();
        };
        throw new IOException(explanation);
    }
    public void upload(String owner, String repo, String token, byte[] bytes)
            throws IOException, InterruptedException {
        String url = endpoint(owner,repo);
        HttpResponse<String> previous = request("GET",url,token,null);
        String sha = null;
        if (previous.statusCode() == 200) sha = extract(previous.body(),"sha");
        else if (previous.statusCode() != 404) expectSuccess(previous, 200);
        String encoded = Base64.getEncoder().encodeToString(bytes);
        String json = "{\"message\":\"TimeBranch cloud snapshot\",\"content\":" + quote(encoded)
                + (sha == null ? "" : ",\"sha\":" + quote(sha)) + "}";
        HttpResponse<String> created = request("PUT",url,token,json);
        expectSuccess(created,200,201);
    }
    public byte[] download(String owner, String repo, String token)
            throws IOException, InterruptedException {
        HttpResponse<String> found = request("GET",endpoint(owner,repo),token,null);
        expectSuccess(found,200);
        try { return Base64.getDecoder().decode(extract(found.body(),"content")); }
        catch (IllegalArgumentException e) { throw new IOException("Cloud backup is corrupted.",e); }
    }
}

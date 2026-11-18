package dev.jefe.mailsorter;

import io.github.cdimascio.dotenv.Dotenv;

import java.net.URI;

public final class Main {
    public static void main(String[] args) throws Exception {

        Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()   // don't crash if the file is absent
                .load();

        String key = dotenv.get("JEV_API_KEY");
        String endpoint = dotenv.get("JEV_ENDPOINT");
        if (key == null || endpoint == null) {
            System.err.println("Set JEV_API_KEY and JEV_ENDPOINT");
            System.exit(1);
        }
        int port = Integer.parseInt(System.getenv().getOrDefault("WEB_PORT", "8080"));
        var web = CompositionRoot.build(new AppConfig("127.0.0.1", port, URI.create(endpoint), key));
        web.start();
        System.out.println("Mail Sorter on http://127.0.0.1:" + port);
    }
}
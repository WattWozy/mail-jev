package dev.jefe.mailsorter;

import io.github.cdimascio.dotenv.Dotenv;

import java.net.URI;
import java.nio.file.Path;

public final class Main {
    public static void main(String[] args) throws Exception {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        AppConfig config;
        try {
            config = AppConfig.from(dotenv::get);
        } catch (IllegalStateException | IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }

        var web = CompositionRoot.build(config);
        web.start();
        System.out.println("Mail Sorter on http://" + config.webHost() + ":" + config.webPort()
                + " (persistence: " + config.persistence() + ")");
    }
}
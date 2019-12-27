package eu.cloudnetservice.cloudnet.repository;

import de.dytanic.cloudnet.common.document.gson.JsonDocument;
import de.dytanic.cloudnet.common.logging.*;
import eu.cloudnetservice.cloudnet.repository.archiver.ReleaseArchiver;
import eu.cloudnetservice.cloudnet.repository.config.BasicConfiguration;
import eu.cloudnetservice.cloudnet.repository.console.ConsoleLogHandler;
import eu.cloudnetservice.cloudnet.repository.database.Database;
import eu.cloudnetservice.cloudnet.repository.database.H2Database;
import eu.cloudnetservice.cloudnet.repository.github.GitHubReleaseInfo;
import eu.cloudnetservice.cloudnet.repository.loader.CloudNetVersionFileLoader;
import eu.cloudnetservice.cloudnet.repository.loader.JenkinsCloudNetVersionFileLoader;
import eu.cloudnetservice.cloudnet.repository.module.ModuleRepositoryProvider;
import eu.cloudnetservice.cloudnet.repository.publisher.UpdatePublisher;
import eu.cloudnetservice.cloudnet.repository.publisher.discord.DiscordUpdatePublisher;
import eu.cloudnetservice.cloudnet.repository.version.CloudNetVersion;
import eu.cloudnetservice.cloudnet.repository.web.handler.ArchivedVersionHandler;
import eu.cloudnetservice.cloudnet.repository.web.handler.GitHubWebHookReleaseEventHandler;
import io.javalin.Javalin;
import io.javalin.http.InternalServerErrorResponse;
import io.javalin.plugin.json.JavalinJson;
import org.fusesource.jansi.AnsiConsole;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Collectors;

public class CloudNetUpdateServer {


    private boolean apiAvailable = System.getProperty("cloudnet.repository.api.enabled", "true").equalsIgnoreCase("true");

    private ReleaseArchiver releaseArchiver;

    private final ModuleRepositoryProvider moduleRepositoryProvider;
    private final Javalin webServer;

    private Collection<UpdatePublisher> updatePublishers = new ArrayList<>();

    private Database database;
    private BasicConfiguration configuration;
    private final ILogger logger;

    private CloudNetUpdateServer() throws IOException {
        this.logger = new DefaultAsyncLogger();
        this.logger.addLogHandler(new DefaultFileLogHandler(new File("logs"), "cloudnet.repo.log", 8000000L).setFormatter(new DefaultLogFormatter()));
        this.logger.addLogHandler(new ConsoleLogHandler(System.out, System.err).setFormatter(new DefaultLogFormatter()));

        AnsiConsole.systemInstall();

        System.setOut(new PrintStream(new LogOutputStream(this.logger, LogLevel.INFO), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new LogOutputStream(this.logger, LogLevel.ERROR), true, StandardCharsets.UTF_8));

        this.configuration = new BasicConfiguration();
        this.configuration.load();

        this.database = new H2Database(Paths.get("database"));

        this.registerUpdatePublisher(new DiscordUpdatePublisher());

        CloudNetVersionFileLoader versionFileLoader = new JenkinsCloudNetVersionFileLoader();
        String gitHubApiBaseUrl = System.getProperty("cloudnet.repository.github.baseUrl", "https://api.github.com/repos/CloudNetService/CloudNet-v3/");
        this.releaseArchiver = new ReleaseArchiver(gitHubApiBaseUrl, versionFileLoader, this.configuration.getVersionFileMappings());

        this.webServer = Javalin.create();

        this.moduleRepositoryProvider = new ModuleRepositoryProvider(this.webServer);

        this.start();

        Runtime.getRuntime().addShutdownHook(new Thread(this::stopWithoutShutdown));
    }

    public void registerUpdatePublisher(UpdatePublisher publisher) {
        this.updatePublishers.add(publisher);
    }

    public Collection<UpdatePublisher> getUpdatePublishers() {
        return this.updatePublishers;
    }

    public CloudNetVersion getCurrentLatestVersion() {
        return this.database.getLatestVersion();
    }

    public Database getDatabase() {
        return this.database;
    }

    public BasicConfiguration getConfiguration() {
        return this.configuration;
    }

    public void start() throws IOException {

        if (!this.database.init()) {
            System.err.println("Failed to initialize the database");
            return;
        }

        for (UpdatePublisher publisher : this.updatePublishers) {
            Path configPath = Paths.get("publishers", publisher.getName() + ".json");
            Files.createDirectories(configPath.getParent());
            publisher.setEnabled(publisher.init(this, configPath));
            if (publisher.isEnabled()) {
                System.out.println("Successfully initialized " + publisher.getName() + " publisher!");
            } else {
                System.err.println("Failed to initialize " + publisher.getName() + " publisher!");
            }
        }

        JavalinJson.setToJsonMapper(JsonDocument.GSON::toJson);
        JavalinJson.setFromJsonMapper(JsonDocument.GSON::fromJson);

        this.webServer.config.requestCacheSize = 16384L;

        this.webServer.config.addStaticFiles("/web");

        this.webServer.get("/versions/:version/*", new ArchivedVersionHandler(Constants.VERSIONS_DIRECTORY, "CloudNet.zip", this));
        this.webServer.get("/docs/:version/*", new ArchivedVersionHandler(Constants.DOCS_DIRECTORY, "index.html", this));

        this.webServer.get("/api", context -> context.result("{\"available\":" + this.apiAvailable + "}"));

        this.webServer.before("/api/*", context -> {
            if (!this.apiAvailable && !context.path().equalsIgnoreCase("/api/")) {
                throw new InternalServerErrorResponse("API currently not available");
            }
        });
        this.webServer.get("/api/versions", context -> context.result(JsonDocument.newDocument().append("versions", Arrays.stream(this.database.getAllVersions()).map(CloudNetVersion::getName).collect(Collectors.toList())).toPrettyJson()));
        this.webServer.get("/api/versions/:version", context -> context.json(this.database.getVersion(context.pathParam("version"))));

        this.webServer.post("/github", new GitHubWebHookReleaseEventHandler(this.configuration.getGitHubSecret(), this));

        this.webServer.start(this.configuration.getWebPort());
    }

    public void stop() {
        this.stopWithoutShutdown();
        System.exit(0);
    }

    private void stopWithoutShutdown() {
        try {
            this.database.close();
            this.webServer.stop();
            this.logger.close();
        } catch (Exception exception) {
            exception.printStackTrace();
        }

        for (UpdatePublisher publisher : this.updatePublishers) {
            publisher.close();
        }
    }

    public void installLatestRelease() {
        try {
            var version = this.releaseArchiver.installLatestRelease();
            this.invokeReleasePublished(version);
            this.database.registerVersion(version);
        } catch (IOException exception) {
            exception.printStackTrace();
        }
    }

    public void installLatestRelease(GitHubReleaseInfo release) {
        try {
            var version = this.releaseArchiver.installLatestRelease(release);
            this.invokeReleasePublished(version);
            this.database.registerVersion(version);
        } catch (IOException exception) {
            exception.printStackTrace();
        }
    }

    private void invokeReleasePublished(CloudNetVersion version) {
        for (UpdatePublisher publisher : this.updatePublishers) {
            publisher.publishRelease(version);
        }
    }

    public static void main(String[] args) throws IOException {
        new CloudNetUpdateServer();
    }

}

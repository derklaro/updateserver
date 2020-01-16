package eu.cloudnetservice.cloudnet.repository;

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
import eu.cloudnetservice.cloudnet.repository.endpoint.EndPoint;
import eu.cloudnetservice.cloudnet.repository.endpoint.discord.DiscordEndPoint;
import eu.cloudnetservice.cloudnet.repository.version.CloudNetParentVersion;
import eu.cloudnetservice.cloudnet.repository.version.CloudNetVersion;
import eu.cloudnetservice.cloudnet.repository.web.WebServer;
import io.javalin.Javalin;
import org.fusesource.jansi.AnsiConsole;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;

public class CloudNetUpdateServer {

    private ReleaseArchiver releaseArchiver;

    private final ModuleRepositoryProvider moduleRepositoryProvider;
    private final WebServer webServer;

    private Collection<EndPoint> endPoints = new ArrayList<>();

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

        this.registerEndPoint(new DiscordEndPoint());

        CloudNetVersionFileLoader versionFileLoader = new JenkinsCloudNetVersionFileLoader();
        this.releaseArchiver = new ReleaseArchiver(versionFileLoader);

        this.webServer = new WebServer(Javalin.create(), this);

        this.moduleRepositoryProvider = new ModuleRepositoryProvider(this);

          //TODO Add console commands (user list, user create, user delete)

        this.start();

        Runtime.getRuntime().addShutdownHook(new Thread(this::stopWithoutShutdown));
    }

    public void registerEndPoint(EndPoint endPoint) {
        this.endPoints.add(endPoint);
    }

    public Collection<EndPoint> getEndPoints() {
        return this.endPoints;
    }

    public CloudNetVersion getCurrentLatestVersion(String parentVersionName) {
        return this.database.getLatestVersion(parentVersionName);
    }

    public Database getDatabase() {
        return this.database;
    }

    public BasicConfiguration getConfiguration() {
        return this.configuration;
    }

    public ModuleRepositoryProvider getModuleRepositoryProvider() {
        return this.moduleRepositoryProvider;
    }

    public Collection<CloudNetParentVersion> getParentVersions() {
        return this.configuration.getParentVersions();
    }

    public Collection<String> getParentVersionNames() {
        return this.getParentVersions().stream().map(CloudNetParentVersion::getName).collect(Collectors.toList());
    }

    public Optional<CloudNetParentVersion> getParentVersion(String name) {
        return this.getParentVersions().stream().filter(parentVersion -> parentVersion.getName().equals(name)).findFirst();
    }

    public void start() throws IOException {

        if (!this.database.init()) {
            System.err.println("Failed to initialize the database");
            return;
        }

        for (EndPoint endPoint : this.endPoints) {
            Path configPath = Paths.get("endPoints", endPoint.getName() + ".json");
            Files.createDirectories(configPath.getParent());
            endPoint.setEnabled(endPoint.init(this, configPath));
            if (endPoint.isEnabled()) {
                System.out.println("Successfully initialized " + endPoint.getName() + " end point!");
            } else {
                System.err.println("Failed to initialize " + endPoint.getName() + " end point!");
            }
        }

        this.webServer.init();
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

        for (EndPoint endPoint : this.endPoints) {
            endPoint.close();
        }
    }

    public void installLatestRelease(CloudNetParentVersion parentVersion) {
        try {
            var version = this.releaseArchiver.installLatestRelease(parentVersion);
            this.invokeReleasePublished(parentVersion, version);
            this.database.registerVersion(version);
        } catch (IOException exception) {
            exception.printStackTrace();
        }
    }

    public void installLatestRelease(CloudNetParentVersion parentVersion, GitHubReleaseInfo release) {
        try {
            var version = this.releaseArchiver.installLatestRelease(parentVersion, release);
            this.invokeReleasePublished(parentVersion, version);
            this.database.registerVersion(version);
        } catch (IOException exception) {
            exception.printStackTrace();
        }
    }

    private void invokeReleasePublished(CloudNetParentVersion parentVersion, CloudNetVersion version) {
        for (EndPoint endPoint : this.endPoints) {
            endPoint.publishRelease(parentVersion, version);
        }
    }

    public static void main(String[] args) throws IOException {
        new CloudNetUpdateServer();
    }

}

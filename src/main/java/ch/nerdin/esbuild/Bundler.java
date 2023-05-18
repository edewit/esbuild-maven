package ch.nerdin.esbuild;

import ch.nerdin.esbuild.modal.BundleOptions;
import ch.nerdin.esbuild.modal.EsBuildConfig;
import ch.nerdin.esbuild.resolve.ExecutableResolver;
import ch.nerdin.esbuild.util.UnZip;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

public class Bundler {


    private static final String WEBJAR_PACKAGE_PREFIX = "META-INF/resources/webjars";
    private static final String MVNPM_PACKAGE_PREFIX = "META-INF/resources/_static";
    private static String VERSION;

    public enum BundleType {
        WEBJARS,
        MVNPM,
    }

    public static String getDefaultVersion() {
        if (VERSION == null) {
            Properties properties = new Properties();
            try {
                final InputStream resource = Bundler.class.getResourceAsStream("/version.properties");
                properties.load(resource);
            } catch (IOException e) {
                // ignore we use the default
            }
            VERSION = properties.getProperty("esbuild.version", "0.17.17");
        }

        return VERSION;
    }

    /**
     * Use esbuild to bundle either webjar or mvnpm dependencies into a bundle.
     *
     * @param bundleOptions options to do the bundling with
     * @return the folder that has the result of the transformation
     * @throws IOException when something could not be written
     */
    public static Path bundle(BundleOptions bundleOptions) throws IOException {
        final Path location = extractDependencies(bundleOptions);
        final Path dist = location.resolve("dist");

        final EsBuildConfig esBuildConfig = createBundle(bundleOptions, location, dist);

        esBuild(esBuildConfig, null);

        return dist;
    }

    private static EsBuildConfig createBundle(BundleOptions bundleOptions, Path location, Path dist) throws IOException {
        final EsBuildConfig esBuildConfig = bundleOptions.getEsBuildConfig();
        Files.createDirectories(dist);
        esBuildConfig.setOutdir(dist.toString());

        final Path path = bundleOptions.getWorkFolder() != null ? bundleOptions.getWorkFolder() : location;
        final List<String> paths = bundleOptions.getEntries().stream().map(entry -> entry.process(path).toString()).toList();
        esBuildConfig.setEntryPoint(paths.toArray(new String[0]));
        return esBuildConfig;
    }

    public static Watch watch(BundleOptions bundleOptions, BuildEventListener eventListener) throws IOException {
        final Path location = extractDependencies(bundleOptions);
        final Path dist = location.resolve("dist");
        final EsBuildConfig esBuildConfig = createBundle(bundleOptions, location, dist);

        bundleOptions.getEsBuildConfig().setWatch(true);
        final Process process = esBuild(esBuildConfig, eventListener);

        return new Watch(process, location, bundleOptions.getType());
    }

    private static Path extractDependencies(BundleOptions bundleOptions) throws IOException {
        final Path bundleDirectory = bundleOptions.getWorkFolder() != null ? bundleOptions.getWorkFolder() : Files.createTempDirectory("bundle");
        return extract(bundleDirectory, bundleOptions.getDependencies(), bundleOptions.getType());
    }

    protected static Path extract(Path bundleDirectory, List<Path> dependencies, BundleType type) throws IOException {
        final Path nodeModules = bundleDirectory.resolve("node_modules");
        if (!Files.exists(nodeModules)) {
            nodeModules.toFile().mkdir();
        }

        for (Path path : dependencies) {
            final NameVersion nameVersion = parseName(path.getFileName().toString());
            final Path packageFolder = nodeModules.resolve(nameVersion.toString());
            UnZip.unzip(path, packageFolder);
            final Path target = nodeModules.resolve(nameVersion.name);
            switch (type) {
                case MVNPM -> Files.move(packageFolder.resolve(MVNPM_PACKAGE_PREFIX).resolve(nameVersion.name), target);
                case WEBJARS -> Files.move(packageFolder.resolve(WEBJAR_PACKAGE_PREFIX).resolve(nameVersion.name)
                        .resolve(nameVersion.version), target);
            }
        }

        return bundleDirectory;
    }

    protected static Process esBuild(EsBuildConfig esBuildConfig, BuildEventListener listener) throws IOException {
        final Path esBuildExec = new ExecutableResolver().resolve(Bundler.getDefaultVersion());
        final Execute execute = new Execute(esBuildExec.toFile(), esBuildConfig);
        if (listener != null) {
            return execute.execute(listener);
        } else {
            execute.executeAndWait();
        }
        return null;
    }

    private static NameVersion parseName(String fileName) {
        final int separatorIndex = fileName.lastIndexOf("-");
        String name = fileName.substring(0, separatorIndex);
        String version = fileName.substring(separatorIndex + 1, fileName.lastIndexOf('.'));

        return new NameVersion(name, version);
    }

    static class NameVersion {
        public String name;
        public String version;

        public NameVersion(String name, String version) {
            this.name = name;
            this.version = version;
        }

        @Override
        public String toString() {
            return "%s-%s".formatted(name, version);
        }
    }
}



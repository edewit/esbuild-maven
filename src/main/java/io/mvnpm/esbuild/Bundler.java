package io.mvnpm.esbuild;

import io.mvnpm.esbuild.model.BundleOptions;
import io.mvnpm.esbuild.model.BundleResult;
import io.mvnpm.esbuild.model.EsBuildConfig;
import io.mvnpm.esbuild.model.ExecuteResult;
import io.mvnpm.esbuild.resolve.ExecutableResolver;
import io.mvnpm.esbuild.util.PackageJson;
import io.mvnpm.esbuild.util.UnZip;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static io.mvnpm.esbuild.util.Copy.deleteRecursive;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Bundler {
    private static final Logger logger = Logger.getLogger(Bundler.class.getName());
    private static final String WEBJAR_PACKAGE_PREFIX = "META-INF/resources/webjars";
    private static final String MVNPM_PACKAGE_PREFIX = "META-INF/resources/_static";
    private static final String NODE_MODULES = "node_modules";
    private static final String DIST = "dist";
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
                if (resource != null) {
                    properties.load(resource);
                }
            } catch (IOException e) {
                // ignore we use the default
            }
            VERSION = properties.getProperty("esbuild.version", "0.17.19");
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
    public static BundleResult bundle(BundleOptions bundleOptions) throws IOException {
        final Path workDir = installIfNeeded(bundleOptions);
        final Path dist = workDir.resolve(DIST);
        final EsBuildConfig esBuildConfig = createBundle(bundleOptions, workDir, dist);

        final ExecuteResult executeResult = esBuild(esBuildConfig);

        if(!Files.isDirectory(dist)) {
            throw new BundleException("Unexpected Error during bundling", executeResult.output());
        }

        return new BundleResult(dist, executeResult);
    }

    private static EsBuildConfig createBundle(BundleOptions bundleOptions, Path workDir, Path dist) throws IOException {
        final EsBuildConfig esBuildConfig = bundleOptions.getEsBuildConfig();
        // Clean the dist directory from a previous bundling
        deleteRecursive(dist);
        Files.createDirectories(dist);
        esBuildConfig.setOutdir(dist.toString());
        final List<String> paths = bundleOptions.getEntries().stream().map(entry -> entry.process(workDir).toString()).toList();
        esBuildConfig.setEntryPoint(paths.toArray(String[]::new));
        return esBuildConfig;
    }

    public static Watch watch(BundleOptions bundleOptions, BuildEventListener eventListener) throws IOException {
        final Path workDir = installIfNeeded(bundleOptions);
        final Path dist = workDir.resolve(DIST);
        final EsBuildConfig esBuildConfig = createBundle(bundleOptions, workDir, dist);

        bundleOptions.getEsBuildConfig().setWatch(true);
        final Process process = esBuild(esBuildConfig, eventListener);
        return new Watch(process, workDir);
    }

    public static Path installIfNeeded(BundleOptions bundleOptions) throws IOException {
        if(bundleOptions.getWorkDir() != null && Files.isDirectory(bundleOptions.getWorkDir().resolve(NODE_MODULES))) {
            return bundleOptions.getWorkDir();
        }
        return install(bundleOptions);
    }

    public static Path install(BundleOptions bundleOptions) throws IOException {
        final Path workingDir = bundleOptions.getWorkDir() != null ? bundleOptions.getWorkDir() : Files.createTempDirectory("bundle");
        return extract(workingDir, bundleOptions.getDependencies(), bundleOptions.getType());
    }

    public static Path install(Path workingDir, List<Path> dependencies, BundleType type) throws IOException {
        return extract(workingDir, dependencies, type);
    }

    public static void clearDependencies(Path workingDir) throws IOException {
        deleteRecursive(workingDir.resolve(NODE_MODULES));
    }

    protected static Path extract(Path bundleDirectory, List<Path> dependencies, BundleType type) throws IOException {
        final Path nodeModules = bundleDirectory.resolve(NODE_MODULES);
        if (!Files.exists(nodeModules)) {
            Files.createDirectories(nodeModules);
        }
        final Path tmp = bundleDirectory.resolve("tmp");
        for (Path path : dependencies) {
            final String fileName = path.getFileName().toString();
            final Path extractDir = tmp.resolve(fileName.substring(0, fileName.lastIndexOf(".")));
            // Only extract new dependencies
            if (!Files.isDirectory(extractDir)) {
                UnZip.unzip(path, extractDir);
                final Optional<Path> packageJson = switch (type) {
                    case MVNPM -> PackageJson.findPackageJson(extractDir.resolve(MVNPM_PACKAGE_PREFIX));
                    case WEBJARS -> PackageJson.findPackageJson(extractDir.resolve(WEBJAR_PACKAGE_PREFIX));
                };
                if(packageJson.isPresent()) {
                    final String packageName = PackageJson.readPackageName(packageJson.get());
                    final Path source = packageJson.get().getParent();
                    final Path target = nodeModules.resolve(packageName);
                    if (!Files.isDirectory(target)) {
                        Files.createDirectories(target.getParent());
                        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        logger.log(Level.INFO, "skipping package as it already exists '{0}'", target);
                    }
                } else {
                    logger.log(Level.INFO,"package.json not found in package '{0}'", fileName);
                }
            }
        }
        return bundleDirectory;
    }

    protected static Process esBuild(EsBuildConfig esBuildConfig, BuildEventListener listener) throws IOException {
        final Path esBuildExec = new ExecutableResolver().resolve(Bundler.getDefaultVersion());
        final Execute execute = new Execute(esBuildExec.toFile(), esBuildConfig);
        return execute.execute(listener);
    }

    protected static ExecuteResult esBuild(EsBuildConfig esBuildConfig) throws IOException {
        final Path esBuildExec = new ExecutableResolver().resolve(Bundler.getDefaultVersion());
        final Execute execute = new Execute(esBuildExec.toFile(), esBuildConfig);
        return execute.executeAndWait();
    }

}


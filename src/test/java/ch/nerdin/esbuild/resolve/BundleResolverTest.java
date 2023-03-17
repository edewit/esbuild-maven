package ch.nerdin.esbuild.resolve;

import ch.nerdin.esbuild.BundleDependencies;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class BundleResolverTest {

    @Test
    public void resolve() throws IOException {
        // when
        final Path resolve = new BundledResolver(null).resolve(BundleDependencies.ESBUILD_VERSION);

        // then
        assertTrue(resolve.toFile().exists());
    }
}

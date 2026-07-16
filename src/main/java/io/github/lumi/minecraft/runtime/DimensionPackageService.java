package io.github.lumi.minecraft.runtime;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.PackageName;
import io.github.lumi.domain.service.ImportExportService;
import io.github.lumi.storage.packageformat.LumiPackageDirectory;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** Owns asynchronous package I/O for one dimension repository. */
final class DimensionPackageService {
    private final ImportExportService history;
    private final LumiPackageDirectory directory;
    private final Executor background;
    private final CurrentBranch currentBranch;

    DimensionPackageService(
            String dimensionId,
            Path repository,
            Path worldRoot,
            Executor background,
            CurrentBranch currentBranch) {
        history = new ImportExportService(
                Objects.requireNonNull(dimensionId, "dimensionId"),
                Objects.requireNonNull(repository, "repository"));
        directory = new LumiPackageDirectory(
                Objects.requireNonNull(worldRoot, "worldRoot"));
        this.background = Objects.requireNonNull(background, "background");
        this.currentBranch = Objects.requireNonNull(currentBranch, "currentBranch");
    }

    CompletableFuture<ImportExportService.PackageInspection> exportPackage(
            PackageName name, BranchRef expected) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(expected, "expected");
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!currentBranch.read().equals(expected)) {
                    throw new IOException("Active branch changed before package export");
                }
                return history.export(expected.commit(), directory.resolve(name));
            } catch (IOException failed) {
                throw new CompletionException(failed);
            }
        }, background);
    }

    CompletableFuture<ImportExportService.PackageInspection> inspectPackage(
            PackageName name) {
        Objects.requireNonNull(name, "name");
        return CompletableFuture.supplyAsync(() -> {
            try {
                return history.inspect(directory.resolve(name));
            } catch (IOException failed) {
                throw new CompletionException(failed);
            }
        }, background);
    }

    CompletableFuture<ImportExportService.ImportResult> importPackage(
            PackageName name,
            ImportExportService.PackageInspection inspection,
            BranchRef expected,
            CommitAuthor author) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(inspection, "inspection");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(author, "author");
        String suffix = inspection.manifest().commit().hex().substring(0, 8);
        BranchName branch = new BranchName(
                "import/" + name.value() + "-" + suffix);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return history.importPackage(
                        directory.resolve(name), inspection, expected,
                        branch, author, Instant.now());
            } catch (IOException failed) {
                throw new CompletionException(failed);
            }
        }, background);
    }

    @FunctionalInterface
    interface CurrentBranch {
        BranchRef read() throws IOException;
    }
}

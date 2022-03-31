package cursedflames.steelwool;

import net.fabricmc.mapping.tree.FieldDef;
import net.fabricmc.mapping.tree.MethodDef;
import net.fabricmc.mapping.tree.TinyMappingFactory;
import net.fabricmc.mapping.tree.TinyTree;
import org.gradle.api.DefaultTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class GenerateMappingsPlugin implements Plugin<Project> {
	private static final String OFFICIAL = "official";
	private static final String INTERMEDIARY = "intermediary";
	private static final String TSRG = "tsrg";

	public void apply(Project project) {
		var task = project.getTasks().register("generateMappings", GenerateMappingsTask.class, generateMappingsTask -> {
			generateMappingsTask.intermediaryJarFile = project.getObjects().fileProperty();
			generateMappingsTask.mcpconfigJarFile = project.getObjects().fileProperty();
			generateMappingsTask.outputFile = project.getObjects().fileProperty();

			generateMappingsTask.intermediaryJarFile.set(project.getConfigurations().getByName("intermediary").getSingleFile());
			generateMappingsTask.mcpconfigJarFile.set(project.getConfigurations().getByName("mcpconfig").getSingleFile());
			generateMappingsTask.outputFile.set(project.getLayout().getBuildDirectory().file("intermediary_to_tsrg.tiny"));
		});
		project.getTasks().getByName("processResources").dependsOn(task);
	}

	public static class GenerateMappingsTask extends DefaultTask {
		RegularFileProperty intermediaryJarFile;

		RegularFileProperty mcpconfigJarFile;

		RegularFileProperty outputFile;

		@InputFile
		RegularFileProperty getIntermediaryJarFile() {
			return intermediaryJarFile;
		}
		@InputFile
		RegularFileProperty getMcpconfigJarFile() {
			return mcpconfigJarFile;
		}
		@OutputFile
		RegularFileProperty getOutputFile() {
			return outputFile;
		}

		@TaskAction
		private void generateMappings() {
			try {
				// TODO handle errors and use proper messages
				var intermediaryJarUri = new URI("jar:" + intermediaryJarFile.get().getAsFile().toURI());
				var mcpconfigJarUri = new URI("jar:" + mcpconfigJarFile.get().getAsFile().toURI());

				TinyTree intermediaryTree;
				try (var intermediaryJar = FileSystems.newFileSystem(intermediaryJarUri, Map.of())) {
					var intermediaryPath = intermediaryJar.getPath("/mappings/mappings.tiny");
					try (var intermediaryReader = Files.newBufferedReader(intermediaryPath)) {
						intermediaryTree = TinyMappingFactory.loadWithDetection(intermediaryReader, true);
					}
				}

				TinyTree tsrgTree;
				try(var mcpconfigJar = FileSystems.newFileSystem(mcpconfigJarUri, Map.of())) {
					var mcpconfigPath = mcpconfigJar.getPath("/config/joined.tsrg");
					try (var mcpconfigReader = Files.newBufferedReader(mcpconfigPath)) {
						// Discard metadata line
						mcpconfigReader.readLine();

						var metadata = "tiny\t2\t0\t" + OFFICIAL + "\t" + TSRG;

						var tsrgTinyData = metadata + "\n" + mcpconfigReader.lines()
								.map(GenerateMappingsPlugin::tsrgLineToTiny)
								.filter(Objects::nonNull)
								.collect(Collectors.joining("\n"));

						var tsrgReader = new BufferedReader(new StringReader(tsrgTinyData));
						tsrgTree = TinyMappingFactory.loadWithDetection(tsrgReader, true);
					}
				}

				var merged = merge(intermediaryTree, tsrgTree);

				try(var writer = new FileWriter(outputFile.get().getAsFile(), false)) {
					writer.write(merged);
				}

			} catch (URISyntaxException | IOException e) {
				throw new UnsupportedOperationException(e);
			}
		}
	}

	private static @Nullable String tsrgLineToTiny(String line) {
		line = line.stripTrailing();
		// Double-indented lines are method parameters and `static`, which we don't care about
		if (line.startsWith("\t\t")) return null;
		// Class mapping
		if (!line.startsWith("\t")) {
			var parts = line.split(" +");
			var official = parts[0];
			var tsrg = parts[1];
			return String.format("c\t%s\t%s", official, tsrg);
		}
		// Either a method or field mapping
		// Discard the leading indent now that we've handled other indentation levels already
		line = line.stripLeading();
		// Skip over constructors and static blocks since they don't matter
		if (line.contains("<init>") || line.contains("<clinit>")) return null;
		var parts = line.split(" +");
		// FIXME these line part counts seem to be inconsistent; different tsrg versions?
		//       some have 1 less term because the id number isn't there
		if (parts.length == 4) {
			// Method
			var official = parts[0];
			var descriptor = parts[1];
			var tsrg = parts[2];
			return String.format("\tm\t%s\t%s\t%s", descriptor, official, tsrg);
		} else if (parts.length == 3) {
			// Field
			var official = parts[0];
			var tsrg = parts[1];
			// Tiny has field types but tsrg doesn't, so we make them all Void.
			// It doesn't matter anyway, since this is just an intermediate step and the type data isn't used anywhere.
			return String.format("\tf\tV\t%s\t%s", official, tsrg);
		} else {
			throw new IllegalStateException("Got an unexpected line in tsrg mappings: " + line);
		}
	}

	private static String merge(TinyTree intermediaryTree, TinyTree tsrgTree) {
		// We only store intermediary and tsrg, except for class names we store official in the tsrg column
		// (since class names have to be changed from official -> mojang at runtime)
		var metadata = "tiny\t2\t0\t" + INTERMEDIARY + "\t" + TSRG;
		var tsrgClasses = tsrgTree.getDefaultNamespaceClassMap();
		var outputBuilder = new StringBuilder(metadata + "\n");
		for (var cls : intermediaryTree.getClasses()) {
			var official = cls.getName(OFFICIAL);
			var intermediary = cls.getName(INTERMEDIARY);
			var tsrg = tsrgClasses.remove(official);
			if (tsrg == null) {
				// TODO
				throw new IllegalStateException();
			} else {
				outputBuilder.append(String.format("c\t%s\t%s\n", intermediary, official));
				var tsrgFields = tsrg.getFields();
				for (var field : cls.getFields()) {
					var fieldOfficial = field.getName(OFFICIAL);
					var fieldIntermediary = field.getName(INTERMEDIARY);
					FieldDef tsrgField = null;
					for (var otherTsrgField : tsrgFields) {
						if (otherTsrgField.getName(OFFICIAL).equals(fieldOfficial)) {
							tsrgField = otherTsrgField;
							break;
						}
					}
					if (tsrgField == null) {
						// TODO
						throw new IllegalStateException();
					}
					outputBuilder.append(String.format("\tf\t%s\t%s\t%s\n", field.getDescriptor(INTERMEDIARY), fieldIntermediary, tsrgField.getName(TSRG)));
				}
				var tsrgMethods = tsrg.getMethods();
				for (var method : cls.getMethods()) {
					var methodOfficial = method.getName(OFFICIAL);
					var methodIntermediary = method.getName(INTERMEDIARY);
					MethodDef tsrgMethod = null;
					for (var otherTsrgMethod : tsrgMethods) {
						if (otherTsrgMethod.getName(OFFICIAL).equals(methodOfficial) && otherTsrgMethod.getDescriptor(OFFICIAL).equals(method.getDescriptor(OFFICIAL))) {
							tsrgMethod = otherTsrgMethod;
							break;
						}
					}
					if (tsrgMethod == null) {
						// TODO
						throw new IllegalStateException();
					}
					outputBuilder.append(String.format("\tm\t%s\t%s\t%s\n", method.getDescriptor(INTERMEDIARY), methodIntermediary, tsrgMethod.getName(TSRG)));
				}
			}
		}
		return outputBuilder.toString();
	}
}

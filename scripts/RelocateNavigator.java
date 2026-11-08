import java.io.*;
import java.util.jar.*;
import org.jetbrains.org.objectweb.asm.*;
import org.jetbrains.org.objectweb.asm.commons.*;

// Retained SBS has an obfuscated Kotlin runtime. Isolate the navigator runtime,
// including all bytecode references, rather than mixing incompatible classes.
public final class RelocateNavigator {
    public static void main(String[] args) throws Exception {
        Remapper mapper = new Remapper() {
            public String map(String name) {
                return name.startsWith("kotlin/") ? "com/gmcnav/runtime/" + name : name;
            }
        };
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(args[0]))) {
            for (int i = 1; i < args.length; i++) {
                try (JarFile in = new JarFile(args[i])) {
                    for (var entries = in.entries(); entries.hasMoreElements();) {
                        var entry = entries.nextElement();
                        if (!entry.getName().endsWith(".class")) continue;
                        ClassReader reader = new ClassReader(in.getInputStream(entry).readAllBytes());
                        ClassWriter writer = new ClassWriter(0);
                        reader.accept(new ClassRemapper(writer, mapper), 0);
                        out.putNextEntry(new JarEntry(mapper.map(reader.getClassName()) + ".class"));
                        out.write(writer.toByteArray());
                        out.closeEntry();
                    }
                }
            }
        }
    }
}

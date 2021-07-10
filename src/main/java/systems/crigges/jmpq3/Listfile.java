package systems.crigges.jmpq3;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Scanner;

import static systems.crigges.jmpq3.HashTable.calculateFileKey;

public class Listfile {
    private final HashMap<Long, String> files = new HashMap<>();

    public Listfile(byte[] file) {
        String list = new String(file, StandardCharsets.UTF_8);
        Scanner sc = new Scanner(list);
        while (sc.hasNextLine()) {
            addFile(sc.nextLine());
        }
        sc.close();
    }

    public Listfile() {
    }

    public Collection<String> getFiles() {
        return this.files.values();
    }

    public HashMap<Long, String> getFileMap() {
        return this.files;
    }

    public void addFile(String name) {
        long key = calculateFileKey(name);
        if (name != null && name.length() > 0 && !this.files.containsKey(key)) {
            this.files.put(key, name);
        }
    }

    public void removeFile(String name) {
        long key = calculateFileKey(name);
        this.files.remove(key);
    }

    public boolean containsFile(String name) {
        long key = calculateFileKey(name);
        return files.containsKey(key);
    }

    public byte[] asByteArray() {
        StringBuilder temp = new StringBuilder();
        for (String entry : this.files.values()) {
            temp.append(entry);
            temp.append("\r\n");
        }
        return temp.toString().getBytes();
    }

}

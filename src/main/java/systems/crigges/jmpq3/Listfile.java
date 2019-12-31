package systems.crigges.jmpq3;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Scanner;

public class Listfile {
    private HashSet<String> files = new HashSet<String>();

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

    public HashSet<String> getFiles() {
        return this.files;
    }

    public void addFile(String name) {
        if (name != null && name.length() > 0 && !this.files.contains(name))
            this.files.add(name);
    }

    public void removeFile(String name) {
        this.files.remove(name);
    }

    public byte[] asByteArray() {
        StringBuilder temp = new StringBuilder();
        for (String s : this.files) {
            temp.append(s);
            temp.append("\r\n");
        }
        return temp.toString().getBytes();
    }

    public String containsEntry(String fileName) {
        String result = files.contains(fileName) ? fileName : "";
        result = !result.equals("") ? (files.contains(fileName.toLowerCase()) ? fileName.toLowerCase() : "") : "";

        String slashCopy = fileName.replaceAll("\\\\", "/");
        result = !result.equals("") ? (files.contains(slashCopy) ? slashCopy : "") : "";
        result = !result.equals("") ? (files.contains(slashCopy.toLowerCase()) ? slashCopy.toLowerCase() : "") : "";
        return result;
    }
}

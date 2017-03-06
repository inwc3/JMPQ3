package systems.crigges.jmpq3;

import java.util.LinkedList;
import java.util.Scanner;

public class Listfile {
    private LinkedList<String> files = new LinkedList();

    public Listfile(byte[] file) {
        String list = new String(file);
        Scanner sc = new Scanner(list);
        while (sc.hasNextLine()) {
            this.files.add(sc.nextLine());
        }
        sc.close();
    }

    public LinkedList<String> getFiles() {
        return this.files;
    }

    public void addFile(String name) {
        if (!this.files.contains(name))
            this.files.add(name);
    }

    public void removeFile(String name) {
        this.files.remove(name);
    }

    public byte[] asByteArray() {
        String temp = "";
        for (String s : this.files) {
            temp = temp + s + "\n";
        }
        return temp.getBytes();
    }
}
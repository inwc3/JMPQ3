[![Build Status](https://travis-ci.org/inwc3/JMPQ3.svg?branch=master)](https://travis-ci.org/inwc3/JMPQ3) [![Coverage Status](https://coveralls.io/repos/github/inwc3/JMPQ-v3/badge.svg?branch=master)](https://coveralls.io/github/inwc3/JMPQ-v3?branch=master)

JMPQ3 is a small java library for accessing and modifying MoPaQ (.mpq,.w3m,.w3x) Archives.
MoPaQ is Blizzard's old, proprietary archive format for storing gamedata (replaced with CASC).
You can find more info and an excellent editor here http://www.zezula.net/en/mpq/main.html
For Java programs however it is neater to rely on Java only instead of some native libraries.

**Download** the latest release here:
https://github.com/inwc3/JMPQ3/releases

Maven artifacts will hopefully be back soon.

Quick API Overview:

Jmpq provides the OpenOptions `READ_ONLY` which should be selfexplanatory and `FORCE_V0` which forces the mpq to be opened like warcraft3 would open it, ignoring optional data from later specifications for compatability.
```java
    JMpqEditor e = new JMpqEditor(new File("my.mpq")); //Opens a new editor
    e.hasFile("filename") //Checks if the file exists
    e.deleteFile("filename"); //Deletes a specific file out of the mpq
    e.extractFile("filename", new File("target location")); //Extracts a specific file out of the mpq to the target location			
    e.insertFile("filename", new File("file to add"), true); //Inserts a specific into the mpq from the target location	
    e.extractAllFiles(new File("target folder")); //Extracts all files inside the mpq to the target folder. If a proper listfile exists, names will be used accordingly
    e.getFileNames(); //Get the listfile as java HashSet<String>
    e.close(); //Rebuilds the mpq and applies all changes that were made. Not needed in READ_ONLY mode
```

###Known issues:
* To work around https://bugs.openjdk.java.net/browse/JDK-4724038 jmpq creates a lot of tempfiles
* JMPQ doesn't support all decompression algorithms. For compression only zlib is implemented.
* JMPQ doesn't build a valid (attributes) file for now. (which seems to be fine for warcraft3)

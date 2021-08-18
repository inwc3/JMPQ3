[![CircleCI](https://circleci.com/gh/inwc3/JMPQ3.svg?style=svg)](https://circleci.com/gh/inwc3/JMPQ3) [![Jit](https://jitpack.io/v/inwc3/JMPQ3.svg)](https://jitpack.io/#inwc3/JMPQ3) [![Coverage Status](https://coveralls.io/repos/github/inwc3/JMPQ3/badge.svg?branch=master)](https://coveralls.io/github/inwc3/JMPQ3?branch=master) [![codebeat badge](https://codebeat.co/badges/5ccfd060-8d57-4a51-9c6b-2688482f857e)](https://codebeat.co/projects/github-com-inwc3-jmpq3-master)
# JMPQ3
**Note: Since Version `1.9.0` jmpq3 requires Java 11 or higher**

## What?
JMPQ3 is a small java library for accessing and modifying mpq (MoPaQ) archives. Common file endings are .mpq, .w3m, .w3x. 

MoPaQ is Blizzard's old, proprietary archive format for storing gamedata (replaced with CASC).

You can find more info and an excellent graphical editor here http://www.zezula.net/en/mpq/main.html

## Get it
*currently only warcraft 3 maps and mpqs are confirmed supported. Mpqs from other games (WoW, starcraft) might cause problems.*

It is recommended to use jitpack with a dependency manager like gradle.

See https://jitpack.io/#inwc3/JMPQ3/

Gradle Example:
```gradle
dependencies {
    compile 'com.github.inwc3:JMPQ3:1.7.14'
}
allprojects {
    repositories {
	maven { url 'https://jitpack.io' }
    }
}
```
You can still download the jar directly if you prefer
https://github.com/inwc3/JMPQ3/releases

## How to use
Quick API Overview:

Jmpq provides the OpenOptions `READ_ONLY` which should be selfexplanatory and `FORCE_V0` which forces the mpq to be opened like warcraft3 would open it, ignoring optional data from later specifications for compatability.
```java
// Automatically rebuilds mpq after use if not in readonly mode
try (JMpqEditor e = new JMpqEditor(new File("my.mpq"), MPQOpenOption.FORCE_V0)){
        e.hasFile("filename"); //Checks if the file exists
        e.extractFile("filename", new File("target location")); //Extracts a specific file out of the mpq to the target location
        if (e.isCanWrite()) {
            e.deleteFile("filename"); //Deletes a specific file out of the mpq
            e.insertFile("filename", new File("file to add"), true); //Inserts a specific into the mpq from the target location
            e.extractAllFiles(new File("target folder")); //Extracts all files inside the mpq to the target folder. If a proper listfile exists,
            e.getFileNames(); //Get the listfile as java HashSet<String>
        }
    }

}
```

### Known issues:
* Unsupported decompression algorithms: sparse and bzip2
* Only supported compression is zlib/zopfli
* JMPQ doesn't build a valid (attributes) file for now. (which seems to be fine for warcraft3)

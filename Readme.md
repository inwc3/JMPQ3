[![Build Status](https://travis-ci.org/inwc3/JMPQ-v3.svg?branch=master)](https://travis-ci.org/inwc3/JMPQ-v3)

JMPQ-v3 is a Small Libary which allows to access and modify MoPaQ Archives

Get the latest Maven Artifacts:
https://mvnrepository.com/artifact/systems.crigges/jmpq3

Quick API Overview:

```java
    JMpqEditor e = new JMpqEditor(new File("my.mpq")); 		//Opens a new editor
    e.deleteFile("filename");								//Deletes a specific file out of the mpq
    e.extractFile("filename", new File("target location"));	//Extracts a specific file out of the mpq to the target location			
    e.insertFile("filename", new File("file to add"), true);//Inserts a specific into the mpq from the target location	
    e.extractAllFiles(new File("target folder"));			//Extracts all files out of the mpq to the target folder
    e.getFileNames();										//Get the listfile as java List<String>
    e.close();												//Rebuilds the mpq and applies all changes which was made
```

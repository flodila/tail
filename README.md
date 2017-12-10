# tail
**tail -f** servlet to push the rear end of a file to your web browser

## Caveat
It's wasteful with open files.
- Having any denial of service intent, it should be easy to externally
    trigger an excess of the open file limit set by your operating system.
- It's for your intranet ___only___.  
    _(You obviously don't want to expose this to the Internet.)_
- Is a production server really the right place for this at all? I don't think
    so.

## Getting started

### Environment it worked on
_(I haven't tested it anywhere else.)_
- Tomcat 9
- Debian ext4 file system  
    _(There an open java.nio.channels.FileChannel always returns the current
    file size.)_

### Steps to run it
- Copy the Java source files into your dynamic web project
- Setup some files in your web.xml
- Run it!  
    _(You need some servlet know-how ...)_

## Architecture

### Goals
1. No dependencies on top of JavaEE
1. No Ajax-Polling. Push it!  
    _(Websocket!)_
1. Use native file system hooks to get notifications on file changes  
    _(java.nio.file.WatchService)_
1. Serve multiple files by having multiple instances of the servlet  
    _(Configured in the web.xml)_
1. As few source code files as possible to be able to easily copy-paste it
    to other Java projects  
    _(I might have to give this up since
    it already became quite a bit more complicated than expected.  
    If you want to try to understand the code it might be best to first refactor
    all the inner classes into their own source code files ...)_

## Todo
- Document
- Unit test
- Make Docker image (test if it runs in OpenJDK)

# tail
tail -f servlet pushing the rear end of a file to your web browser

## Getting started
- Use Tomcat 9 (I haven't tested it anywhere else.)
- Copy the tree Java source files into your dynamic web project
- Setup some files in your web.xml
- Run it!

## Architecture

### Goals
1. No dependencies on top of JavaEE
1. No Ajax-Polling. Push it!  
    _(Websocket!)_
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

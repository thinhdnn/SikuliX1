@echo off
SETLOCAL ENABLEEXTENSIONS
set ide=C:\Users\rmhde\IdeaProjects\SikuliX1\IDE\target\sikulixide-2.0.6-SNAPSHOT-complete-win.jar
set java="C:\Program Files\EclipseJDK\jdk11\bin\java.exe"
%java% -jar %ide% %*
ENDLOCAL

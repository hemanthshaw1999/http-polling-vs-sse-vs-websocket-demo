@echo off
setlocal
call "%~dp0mvnw.cmd" spring-boot:run %*
exit /b %ERRORLEVEL%

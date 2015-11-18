@if "%DEBUG%" == "" @echo off
@rem ##########################################################################
@rem
@rem  publisher startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

@rem Add default JVM options here. You can also use JAVA_OPTS and PUBLISHER_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS=

set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%..

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if "%ERRORLEVEL%" == "0" goto init

echo.
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.

goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto init

echo.
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.

goto fail

:init
@rem Get command-line arguments, handling Windowz variants

if not "%OS%" == "Windows_NT" goto win9xME_args
if "%@eval[2+2]" == "4" goto 4NT_args

:win9xME_args
@rem Slurp the command line arguments.
set CMD_LINE_ARGS=
set _SKIP=2

:win9xME_args_slurp
if "x%~1" == "x" goto execute

set CMD_LINE_ARGS=%*
goto execute

:4NT_args
@rem Get arguments from the 4NT Shell from JP Software
set CMD_LINE_ARGS=%$

:execute
@rem Setup the command line

set CLASSPATH=%APP_HOME%\lib\publisher-1.0.jar;%APP_HOME%\lib\scala-library-2.11.2.jar;%APP_HOME%\lib\scala-async_2.11-0.9.2.jar;%APP_HOME%\lib\akka-actor_2.11-2.3.6.jar;%APP_HOME%\lib\config-1.2.1.jar;%APP_HOME%\lib\rxjava-scala-0.20.4.jar;%APP_HOME%\lib\postgresql-9.3-1101-jdbc41.jar;%APP_HOME%\lib\mariadb-java-client-1.1.7.jar;%APP_HOME%\lib\h2-1.4.181.jar;%APP_HOME%\lib\commons-dbcp2-2.0.1.jar;%APP_HOME%\lib\commons-codec-1.9.jar;%APP_HOME%\lib\spring-context-4.1.1.RELEASE.jar;%APP_HOME%\lib\spring-jdbc-4.1.1.RELEASE.jar;%APP_HOME%\lib\spring-tx-4.1.1.RELEASE.jar;%APP_HOME%\lib\rxjava-core-0.20.4.jar;%APP_HOME%\lib\jna-3.3.0.jar;%APP_HOME%\lib\jna-3.3.0-platform.jar;%APP_HOME%\lib\commons-pool2-2.2.jar;%APP_HOME%\lib\commons-logging-1.1.3.jar;%APP_HOME%\lib\spring-aop-4.1.1.RELEASE.jar;%APP_HOME%\lib\spring-beans-4.1.1.RELEASE.jar;%APP_HOME%\lib\spring-core-4.1.1.RELEASE.jar;%APP_HOME%\lib\spring-expression-4.1.1.RELEASE.jar;%APP_HOME%\lib\aopalliance-1.0.jar

@rem Execute publisher
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %PUBLISHER_OPTS%  -classpath "%CLASSPATH%" Application %CMD_LINE_ARGS%

:end
@rem End local scope for the variables with windows NT shell
if "%ERRORLEVEL%"=="0" goto mainEnd

:fail
rem Set variable PUBLISHER_EXIT_CONSOLE if you need the _script_ return code instead of
rem the _cmd.exe /c_ return code!
if  not "" == "%PUBLISHER_EXIT_CONSOLE%" exit 1
exit /b 1

:mainEnd
if "%OS%"=="Windows_NT" endlocal

:omega

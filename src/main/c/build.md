# Build with mingw-64

- Use the build command `build-mingw64.cmd`. If it doesn't work, it's probably because
  - you have a different version of the JDK. I have 1.8.0_51
  - you haven't built bitcoin core and you don't have the import dll in `/usr/local/lib`
  
- At runtime, you need libbitcoinconsensus-0.dll or equivalent in your java library path

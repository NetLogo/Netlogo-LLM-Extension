# Installing the LLM Extension

## Quick Install (Recommended)

### 1. Build the Extension

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
sbt clean assembly
```

### 2. Install to NetLogo

**macOS:**
```bash
mkdir -p "/Applications/NetLogo 7.0.4/extensions/llm"
cp target/scala-3.7.0/llm.jar "/Applications/NetLogo 7.0.4/extensions/llm/"
```

**Windows:**
```bash
mkdir "C:\Program Files\NetLogo 7.0.4\extensions\llm"
copy target\scala-3.7.0\llm.jar "C:\Program Files\NetLogo 7.0.4\extensions\llm\"
```

**Linux:**
```bash
mkdir -p "/usr/local/NetLogo-7.0.0/extensions/llm"
cp target/scala-3.7.0/llm.jar "/usr/local/NetLogo-7.0.0/extensions/llm/"
```

## Install from ZIP Package (Alternative)

If you have a pre-built `llm-1.1.0.zip`:

### 1. Extract the ZIP

```bash
unzip llm-1.1.0.zip
```

### 2. Copy to NetLogo

**macOS:**
```bash
cp -r llm-1.1.0 "/Applications/NetLogo 7.0.4/extensions/llm"
```

**Windows:**
```bash
xcopy llm-1.1.0 "C:\Program Files\NetLogo 7.0.4\extensions\llm" /E /I
```

**Linux:**
```bash
cp -r llm-1.1.0 "/usr/local/NetLogo-7.0.0/extensions/llm"
```

## Verify Installation

### 1. Open NetLogo

Launch NetLogo 7.0.4 (or newer)

### 2. Test the Extension

Open the Command Center and type:

```netlogo
extensions [llm]
```

If no error appears, the extension is installed correctly.

## Using the Extension

### 1. Create a Config File

Create a `config.txt` file next to your NetLogo model with your API keys:

```
provider = openai
api_key = sk-your-api-key-here
model = gpt-4o
temperature = 0.7
max_tokens = 500
```

See `demos/config-reference.txt` for all options.

### 2. Load Config in Your Model

In your NetLogo code:

```netlogo
extensions [llm]

to setup
  llm:load-config "config.txt"
end

to test-llm
  let response llm:chat "Hello, what is 2+2?"
  print response
end
```

### 3. Run Your Model

Click Setup, then run `test-llm` from the Command Center.

## NetLogo Extensions Directory

The extension must be in NetLogo's `extensions/llm/` folder:

**macOS:**
- `/Applications/NetLogo 7.0.4/extensions/llm/`

**Windows:**
- `C:\Program Files\NetLogo 7.0.4\extensions\llm\`

**Linux:**
- `/usr/local/NetLogo-7.0.0/extensions/llm/`

**Expected structure:**
```
NetLogo 7.0.4/
└── extensions/
    └── llm/
        └── llm.jar          (for Fat JAR install)

OR

NetLogo 7.0.4/
└── extensions/
    └── llm/
        ├── llm.jar
        ├── cats-core_3-2.9.0.jar
        ├── circe-yaml_3-0.15.0.jar
        └── [other JARs]     (for ZIP install)
```

## Troubleshooting

**Problem**: "Extension llm not found"
**Solution**: Check that you have:
- Created the folder: `extensions/llm/`
- Copied the file: `extensions/llm/llm.jar`

**Problem**: "NoClassDefFoundError: ujson/Value"
**Solution**: You're using an old JAR. Rebuild with:
```bash
sbt clean assembly
```

**Problem**: Config file not found
**Solution**: Make sure `config.txt` is in the same folder as your `.nlogo` file, or use the full path:
```netlogo
llm:load-config "/full/path/to/config.txt"
```

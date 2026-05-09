with open('app/src/main/java/com/jonas/x24/MainActivity.kt', 'r') as f:
    content = f.read()

# Let's see if Dispatchers is imported.
if "import kotlinx.coroutines.Dispatchers" not in content:
    content = content.replace("import kotlinx.coroutines.CoroutineScope", "import kotlinx.coroutines.CoroutineScope\nimport kotlinx.coroutines.Dispatchers")

if "import kotlinx.coroutines.withContext" not in content:
    content = content.replace("import kotlinx.coroutines.CoroutineScope", "import kotlinx.coroutines.CoroutineScope\nimport kotlinx.coroutines.withContext")

with open('app/src/main/java/com/jonas/x24/MainActivity.kt', 'w') as f:
    f.write(content)

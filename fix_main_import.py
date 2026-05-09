import re
with open('app/src/main/java/com/jonas/x24/MainActivity.kt', 'r') as f:
    content = f.read()

# Make absolutely sure we have withContext
if "import kotlinx.coroutines.withContext" not in content:
    content = content.replace("import kotlinx.coroutines.launch", "import kotlinx.coroutines.launch\nimport kotlinx.coroutines.withContext")

with open('app/src/main/java/com/jonas/x24/MainActivity.kt', 'w') as f:
    f.write(content)

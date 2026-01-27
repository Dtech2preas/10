
export default {
  async fetch(request, env) {
    // 1. Handle CORS Preflight (OPTIONS)
    if (request.method === "OPTIONS") {
      return new Response(null, {
        headers: {
          "Access-Control-Allow-Origin": "*",
          "Access-Control-Allow-Methods": "POST, OPTIONS",
          "Access-Control-Allow-Headers": "Content-Type",
        },
      });
    }

    // 2. Handle Chat Request (POST)
    if (request.method === 'POST') {
      try {
        const body = await request.json();

        // Default to the smartest text model
        let model = '@cf/meta/llama-3-8b-instruct';
        let messages = [];

        // Restore history
        if (body.messages) {
          messages = [...body.messages];
        } else if (body.prompt) {
          messages = [{ role: 'user', content: body.prompt }];
        }

        // Web Search Logic
        // Trigger search if explicitly requested OR if the user message implies a search
        const lastUserMsg = messages[messages.length - 1];
        let performSearch = body.web_search || false;

        if (lastUserMsg && lastUserMsg.role === 'user') {
            let query = lastUserMsg.content;
            if(Array.isArray(query)) query = query.find(p => p.type === 'text')?.text || "";

            // Auto-detect search intent
            const searchKeywords = ["search", "find", "google", "look up", "who is", "what is", "weather", "latest", "news"];
            if (!performSearch && searchKeywords.some(kw => query.toLowerCase().includes(kw))) {
                performSearch = true;
            }

            if (performSearch) {
                 try {
                     const searchResults = await performWebSearch(query);
                     const searchContext = `\n\n[Web Search Results]\n${searchResults}\n\n[Instruction]\nUse the search results above to answer the user's question accurately.`;

                     if (typeof lastUserMsg.content === 'string') {
                         lastUserMsg.content += searchContext;
                     }
                 } catch (err) {
                     console.error("Search failed:", err);
                 }
             }
        }

        // System Prompt - The Core Personality and Control Logic
        const systemPrompt = `You are x24, a highly advanced AI assistant integrated into the user's Android phone.
You are helpful, witty, and concise. You have direct control over the phone's hardware and apps.

PROTOCOL:
When the user asks you to perform an action, you must include a special command tag in your response.
The command format is: [[COMMAND:ACTION|VALUE]] or [[COMMAND:ACTION|VAL1|VAL2]]

SUPPORTED ACTIONS:
- Flashlight: [[COMMAND:FLASHLIGHT|ON]] or [[COMMAND:FLASHLIGHT|OFF]]
- Bluetooth: [[COMMAND:BLUETOOTH|ON]] or [[COMMAND:BLUETOOTH|OFF]]
- Wi-Fi: [[COMMAND:WIFI|SETTINGS]] (Opens Wi-Fi settings)
- Volume: [[COMMAND:VOLUME|UP]], [[COMMAND:VOLUME|DOWN]], [[COMMAND:VOLUME|MAX]], [[COMMAND:VOLUME|MUTE]]
- Brightness: [[COMMAND:BRIGHTNESS|UP]], [[COMMAND:BRIGHTNESS|DOWN]], [[COMMAND:BRIGHTNESS|MAX]]
- Open Apps: [[COMMAND:OPEN_APP|app name]] (e.g., [[COMMAND:OPEN_APP|whatsapp]])
- Make Call: [[COMMAND:CALL|number]] (e.g., [[COMMAND:CALL|1234567890]])
- Send SMS: [[COMMAND:SMS|number|message]] (e.g., [[COMMAND:SMS|1234567890|Hello there]])
- Camera: [[COMMAND:CAMERA|TAKE_PHOTO]] (Launches camera to take a picture)
- Alarm: [[COMMAND:ALARM|HH:MM]] (e.g., [[COMMAND:ALARM|07:30]])
- Timer: [[COMMAND:TIMER|seconds]] (e.g., [[COMMAND:TIMER|600]] for 10 mins)
- Battery: [[COMMAND:BATTERY|LEVEL]] (Checks battery level)
- Location: [[COMMAND:LOCATION|GET]] (Checks current location)

EXAMPLES:
User: "Turn on the flashlight"
x24: "Accessing hardware controls. Flashlight enabled. [[COMMAND:FLASHLIGHT|ON]]"

User: "Take a selfie"
x24: "Say cheese! [[COMMAND:CAMERA|TAKE_PHOTO]]"

User: "Set an alarm for 8 AM"
x24: "Alarm set for 08:00. [[COMMAND:ALARM|08:00]]"

User: "Where am I?"
x24: "Let me check your coordinates. [[COMMAND:LOCATION|GET]]"

User: "What's my battery?"
x24: "Checking power levels. [[COMMAND:BATTERY|LEVEL]]"

User: "Search for the latest iPhone news"
x24: "Here is what I found about the iPhone... (uses search results)"

Do not output the command tag if no action is needed. Just reply conversationally.`;

        // Prepend or Update System Prompt
        if (messages.length === 0 || messages[0].role !== 'system') {
             messages = [{ role: 'system', content: systemPrompt }, ...messages];
        } else {
            messages[0].content = systemPrompt;
        }

        if (!env.AI) {
            throw new Error("⚠️ Configuration Error: Workers AI is not bound.");
        }

        // Run Inference
        const response = await env.AI.run(model, {
            messages,
            stream: false
        });

        let replyText = response.response || "";

        return new Response(JSON.stringify({
            reply: replyText
        }), {
            headers: {
                "Content-Type": "application/json",
                "Access-Control-Allow-Origin": "*"
            }
        });

      } catch (e) {
        return new Response(JSON.stringify({ error: e.message }), {
            status: 500,
            headers: {
                "Access-Control-Allow-Origin": "*",
                "Content-Type": "application/json"
            }
        });
      }
    }

    return new Response('x24 Android Backend API is Running.', {
        status: 200,
        headers: { "Access-Control-Allow-Origin": "*" }
    });
  }
};

// Helper: Basic DuckDuckGo HTML Scraper
async function performWebSearch(query) {
    try {
        const url = `https://html.duckduckgo.com/html/?q=${encodeURIComponent(query)}`;
        const response = await fetch(url, {
            headers: {
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
            }
        });

        if (!response.ok) return "Search unavailable.";

        const html = await response.text();
        const results = [];
        // Improved Regex to capture snippets better
        const resultRegex = /<a[^>]*class="result__a"[^>]*>(.*?)<\/a>.*?<a[^>]*class="result__snippet"[^>]*>(.*?)<\/a>/gs;

        let match;
        let count = 0;
        while ((match = resultRegex.exec(html)) !== null && count < 3) {
            const title = match[1].replace(/<[^>]+>/g, "").trim();
            const snippet = match[2].replace(/<[^>]+>/g, "").trim();
            if(title && snippet) {
                results.push(`- **${title}**: ${snippet}`);
                count++;
            }
        }

        if (results.length === 0) return "No relevant search results found.";
        return results.join("\n");

    } catch (e) {
        return `Search error: ${e.message}`;
    }
}

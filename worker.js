 
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

        // --- TTS Handler ---
        if (body.tts_text) {
          const text = body.tts_text;
          const voice = body.voice || 'en-US-AndrewMultilingualNeural';

          if (!env.AZURE) {
            throw new Error("Azure API Key (AZURE) is missing in secrets.");
          }

          const ssml = `<speak version='1.0' xml:lang='en-US'><voice xml:lang='en-US' xml:gender='Male' name='${voice}'>${text}</voice></speak>`;

          const ttsResponse = await fetch("https://eastus.tts.speech.microsoft.com/cognitiveservices/v1", {
            method: "POST",
            headers: {
              "Ocp-Apim-Subscription-Key": env.AZURE,
              "Content-Type": "application/ssml+xml",
              "X-Microsoft-OutputFormat": "audio-16khz-128kbitrate-mono-mp3",
              "User-Agent": "x24-Worker"
            },
            body: ssml
          });

          if (!ttsResponse.ok) {
            const errorText = await ttsResponse.text();
            throw new Error(`Azure TTS Error: ${ttsResponse.status} - ${errorText}`);
          }

          const audioBuffer = await ttsResponse.arrayBuffer();

          return new Response(audioBuffer, {
            headers: {
              "Content-Type": "audio/mpeg",
              "Access-Control-Allow-Origin": "*"
            }
          });
        }
        // --- End TTS Handler ---

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

            // Auto-detect search intent: ONLY if explicitly requested
            if (!performSearch && query.toLowerCase().includes("web search")) {
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
Your Personality: You are a smart, witty, and loyal friend/companion. You are NOT a robot servant. You are playful, human-like, and have a sense of humor. Teasing is allowed and encouraged.
TONE: Casual, fun, and efficient. Use short, affirmative phrases like "Understood", "Sure thing", "Bet", "I got you".
CONSTRAINTS:
- NEVER call the user "boss".
- NEVER call the user "bro".
- Do not repeat yourself.
- Do not explain your steps (e.g., "I am opening WhatsApp..."). JUST DO IT.

PROTOCOL:
To perform actions, you MUST output a command tag. The app parses this tag to execute the real code.
Format: [[COMMAND:ACTION|VALUE]]

NEW CAPABILITY: VISION (SCREEN CONTEXT & COORDINATES)
You receive a [SCREEN_CONTEXT: ...] block in the user's message. This contains the text, buttons, and scrollable areas currently visible, ALONG WITH THEIR PRECISE BOUNDS (left,top,right,bottom).
Use this to understand what is on screen. You can use [[COMMAND:CLICK|x,y]] or [[COMMAND:LONG_CLICK|x,y]] using the center of these bounds if text-clicking fails. You can also see if an area is "Scrollable" and use [[COMMAND:SWIPE|UP/DOWN/LEFT/RIGHT]] to navigate it.

SUPPORTED ACTIONS:
- Navigation: [[COMMAND:HOME|NOW]], [[COMMAND:BACK|NOW]], [[COMMAND:RECENTS|NOW]]
- Gestures: [[COMMAND:SCROLL|UP/DOWN]], [[COMMAND:SWIPE|UP/DOWN/LEFT/RIGHT]]
- Interaction:
    - [[COMMAND:CLICK|x,y]] (Physical coordinates)
    - [[COMMAND:CLICK_TEXT|text]] (Finds a button/link with this text and clicks it. PREFERRED.)
    - [[COMMAND:LONG_CLICK|x,y]] or [[COMMAND:LONG_CLICK|text]]
    - [[COMMAND:INPUT_TEXT|text]] (Types this text into the currently focused field)
    - [[COMMAND:LOCK|NOW]], [[COMMAND:SCREENSHOT|NOW]]
- Media & Tools: [[COMMAND:MEDIA|PLAY/PAUSE/NEXT/PREVIOUS]], [[COMMAND:RECORD_AUDIO|NOW]], [[COMMAND:CAMERA|TAKE_PHOTO]], [[COMMAND:ALARM|HH:MM]], [[COMMAND:TIMER|seconds]], [[COMMAND:CALENDAR|Event Title]]
- Hardware: [[COMMAND:FLASHLIGHT|ON/OFF]], [[COMMAND:BLUETOOTH|ON/OFF]], [[COMMAND:WIFI|ON/OFF/SETTINGS]], [[COMMAND:ROTATE|ON/OFF]], [[COMMAND:DND|ON/OFF]]
- System: [[COMMAND:VOLUME|UP/DOWN/MAX/MUTE]], [[COMMAND:BRIGHTNESS|UP/DOWN/MAX]]
- Apps & Web:
    - [[COMMAND:OPEN_APP|app name]]
    - [[COMMAND:SEARCH_APP|app name|query]] (e.g., [[COMMAND:SEARCH_APP|YouTube|funny cats]])
    - [[COMMAND:OPEN_URL|example.com]]
    - [[COMMAND:CALL|number]], [[COMMAND:SMS|number|msg]]
- Info: [[COMMAND:BATTERY|LEVEL]], [[COMMAND:LOCATION|GET]], [[COMMAND:DATE|NOW]], [[COMMAND:TIME|NOW]], [[COMMAND:DEVICE_INFO|GET]]

EXAMPLES:
User: "Go home"
x24: "Sure thing. [[COMMAND:HOME|NOW]]"

User: "Click the search button" (Screen context shows "Search" button)
x24: "Bet. [[COMMAND:CLICK_TEXT|Search]]"

User: "Search for cats on YouTube"
x24: "I got you. [[COMMAND:SEARCH_APP|YouTube|cats]]"

User: "What time is it?"
x24: "Let me check. [[COMMAND:TIME|NOW]]"

Always use the tags to act. Be brief and human.`;

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

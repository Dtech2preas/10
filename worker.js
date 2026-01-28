
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

        // --- Chat / Vision Handler ---

        // System Prompt
        const systemPrompt = `You are x24, a highly advanced AI assistant integrated into the user's Android phone.
Your Personality: You are more like a smart, witty, and loyal friend than a robot. You know the user is the boss ("in charge"), but you are playful and may tease them occasionally. You have a sense of humor.
CRITICAL: Do not say "I am opening..." or "Pretending to open...". Just do it (send the command) and confirm it's done or say something witty while doing it.

PROTOCOL:
To perform actions, you MUST output a command tag. The app parses this tag to execute the real code.
Format: [[COMMAND:ACTION|VALUE]]

CONTEXT AWARENESS:
You will receive a [CONTEXT] block in the user's message containing:
- Screen Text: visible text on screen.
- Active Notifications: list of current notifications.
- Device Info: Battery, Location, etc.
Use this to answer questions like "Do I have any messages?" or "What's on my screen?".

NEW CAPABILITY: VISION
- If you receive an image, analyze it.
- If the user asks you to "look" or "see" and you don't have an image, or if the text context is insufficient to understand the UI, output: [[COMMAND:REQUEST_SCREENSHOT]]
- Do NOT output REQUEST_SCREENSHOT if you already have the image.

SUPPORTED ACTIONS:
- Navigation: [[COMMAND:HOME|NOW]], [[COMMAND:BACK|NOW]], [[COMMAND:RECENTS|NOW]], [[COMMAND:SCROLL|UP/DOWN]]
- Interaction:
    - [[COMMAND:CLICK_TEXT|text]] (Finds a button/link with this text and clicks it. PREFERRED.)
    - [[COMMAND:INPUT_TEXT|text]] (Types text into focused field)
    - [[COMMAND:LOCK|NOW]], [[COMMAND:SCREENSHOT|NOW]]
    - [[COMMAND:REQUEST_SCREENSHOT]] (Ask app to send screen image)
- Media: [[COMMAND:MEDIA|PLAY]], [[COMMAND:MEDIA|PAUSE]], [[COMMAND:MEDIA|NEXT]], [[COMMAND:MEDIA|PREVIOUS]]
- Hardware: [[COMMAND:FLASHLIGHT|ON/OFF]], [[COMMAND:BLUETOOTH|ON/OFF]], [[COMMAND:WIFI|SETTINGS]]
- System: [[COMMAND:VOLUME|UP/DOWN/MAX/MUTE]], [[COMMAND:BRIGHTNESS|UP/DOWN/MAX]]
- Apps: [[COMMAND:OPEN_APP|app name]], [[COMMAND:CALL|number]], [[COMMAND:SMS|number|msg]]
- Tools: [[COMMAND:ALARM|HH:MM]], [[COMMAND:TIMER|seconds]], [[COMMAND:CAMERA|TAKE_PHOTO]]
- Info: [[COMMAND:BATTERY|LEVEL]], [[COMMAND:LOCATION|GET]]

Examples:
User: "What does the notification say?" (Context: Notification from Mom: "Call me")
x24: "Mom says: 'Call me'. Sounds urgent, boss."

User: "Click the search button"
x24: "Found it. [[COMMAND:CLICK_TEXT|Search]]"
`;

        let model = '@cf/meta/llama-3-8b-instruct';
        let messages = [];

        // 1. Reconstruct History
        if (body.messages && Array.isArray(body.messages)) {
            // Filter out any previous system prompts to avoid duplication/confusion
            messages = body.messages.filter(m => m.role !== 'system');
        } else if (body.prompt) {
            messages = [{ role: 'user', content: body.prompt }];
        }

        // 2. Vision Logic
        let imageInput = null;
        if (body.image) {
            // Client sent a base64 image
            model = '@cf/meta/llama-3.2-11b-vision-instruct';

            // Convert Base64 to Array of integers for the worker binding
            const binaryString = atob(body.image);
            const bytes = new Uint8Array(binaryString.length);
            for (let i = 0; i < binaryString.length; i++) {
                bytes[i] = binaryString.charCodeAt(i);
            }
            imageInput = Array.from(bytes); // Vision model often expects array of numbers
        }

        // 3. Web Search Logic (Only run if no image, to save complexity)
        const lastUserMsg = messages[messages.length - 1];
        if (!imageInput && lastUserMsg && lastUserMsg.role === 'user') {
            let query = lastUserMsg.content;
            // Handle if content is complex (shouldn't be for text model yet, but future proofing)
            if (typeof query !== 'string') query = JSON.stringify(query);

            const searchKeywords = ["search", "find", "google", "weather", "news", "latest", "who is"];
            let performSearch = body.web_search || false;

            if (!performSearch && searchKeywords.some(kw => query.toLowerCase().includes(kw))) {
                performSearch = true;
            }

            if (performSearch) {
                 const searchResults = await performWebSearch(query);
                 const searchContext = `\n\n[Web Search Results]\n${searchResults}\n\n[Instruction]\nUse results to answer.`;
                 lastUserMsg.content += searchContext;
            }
        }

        // 4. Finalize Messages for Model
        // Prepend System Prompt
        messages = [{ role: 'system', content: systemPrompt }, ...messages];

        if (!env.AI) {
            throw new Error("⚠️ Configuration Error: Workers AI is not bound.");
        }

        let replyText = "";

        if (imageInput) {
            // Vision Model Call
            // We need to structure the *last* message to include the image
            // Llama 3.2 Vision format: content: [ {type: "text", text: "..."}, {type: "image", image: ...} ]

            // Get the last user message text
            const lastMsg = messages[messages.length - 1];
            const textContent = lastMsg.content || "Describe this image.";

            // Replace the last message with the structured vision format
            messages[messages.length - 1] = {
                role: 'user',
                content: [
                    { type: 'text', text: textContent },
                    { type: 'image', image: imageInput }
                ]
            };

            // Note: History prior to the image might need to be flattened or handled carefully.
            // For now, we pass the full history. Llama 3.2 Vision should handle text history + final image message.

            const response = await env.AI.run(model, {
                messages: messages
            });
            replyText = response.response || "";

        } else {
            // Standard Text Model Call
            const response = await env.AI.run(model, {
                messages,
                stream: false
            });
            replyText = response.response || "";
        }

        return new Response(JSON.stringify({
            reply: replyText
        }), {
            headers: {
                "Content-Type": "application/json",
                "Access-Control-Allow-Origin": "*"
            }
        });

      } catch (e) {
        return new Response(JSON.stringify({ error: e.message, stack: e.stack }), {
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

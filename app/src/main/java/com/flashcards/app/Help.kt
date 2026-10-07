@file:OptIn(ExperimentalMaterial3Api::class)

package com.flashcards.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val helpSections: List<Pair<String, String>> = listOf(
    "1. What is this app?" to
        "This app helps you remember things. It uses flashcards, like little paper cards.\n\n" +
        "One side has a question, for example: \"What is the capital of France?\". You think about it, then turn the card and see the answer: \"Paris\".\n\n" +
        "The clever part: the app remembers which cards were easy and which were hard. Hard cards come back soon. Easy cards come back much later. " +
        "So you spend your time on the things you do not know yet.",

    "2. Decks and cards" to
        "A DECK is a box of cards about one subject. Example: a deck called \"Physics Chapter 3\".\n\n" +
        "To make a deck: on the first screen tap + Create Deck, type a name, tap Create.\n" +
        "To open a deck: tap its name.\n" +
        "To add a card: open the deck and tap + Add card.\n" +
        "To change a card: tap it in the list. To delete a card: open it, tap the three dots, tap Delete card.\n" +
        "To rename or delete a whole deck: open the deck and use the three dots or the bin icon at the top.",

    "3. Faces (the sides of a card)" to
        "A normal paper card has 2 sides. Here a card can have up to 5 FACES.\n\n" +
        "Example card:\n" +
        "Face 1: What is force?\n" +
        "Face 2: Hint: think about Newton.\n" +
        "Face 3: Force = mass times acceleration\n" +
        "Face 4: A bigger push makes a heavier thing speed up more slowly.\n" +
        "Face 5: A recording of you explaining it.\n\n" +
        "When you study, you see Face 1 first. Tap the card (or the Show next face button) to see Face 2, and so on. " +
        "On the last face the four answer buttons appear.\n" +
        "You do not have to use all 5. Two faces is perfectly fine. Use + Face and Remove face in the editor.",

    "4. Writing text and maths formulas" to
        "In the card editor each face has little boxes. Tap + Text to add a box for normal words.\n\n" +
        "FORMULAS: you do not have to build formulas with buttons. Just PASTE them. " +
        "Copy a formula from anywhere (a website, ChatGPT, a PDF written in LaTeX) and paste it into a box. It turns into real maths.\n\n" +
        "These all work, with or without the little signs around them:\n" +
        "\\frac{1}{2}mv^2\n" +
        "\\(E = mc^2\\)\n" +
        "\$\$ x = \\frac{-b \\pm \\sqrt{b^2-4ac}}{2a} \$\$\n\n" +
        "Tip: tap + Formula to make a box that is only for a formula. Anything you paste there becomes maths. " +
        "You can see the result in the Preview under the boxes. You can also write words and a formula in the same Text box, " +
        "if the formula has the signs around it, for example: The energy is \\(E = mc^2\\).",

    "5. Pictures" to
        "Tap + Image to choose a picture from your gallery.\n\n" +
        "To use a screenshot: take the screenshot, copy it (most phones have a Copy button after a screenshot), open the card editor and tap Paste image.\n" +
        "If the phone does not allow it, the app tells you and you can use + Image instead.\n\n" +
        "Big pictures are made smaller automatically so your phone does not fill up. Text in pictures stays readable.\n" +
        "While studying, tap a picture to see it big. Use two fingers to zoom.",

    "6. Your own voice" to
        "Tap + Audio in the editor. Then tap Record and talk. Tap Stop when you finish.\n" +
        "The first time, your phone asks if the app may use the microphone. Tap Allow. If you say no, everything else still works.\n\n" +
        "After recording you can Play it, Re-record it, or delete it with the bin. " +
        "You can put a recording on any face, for example as the answer. While studying, tap Play recording.",

    "7. Handwriting (pen cards)" to
        "If you have a stylus or just like writing with your finger:\n\n" +
        "In a deck tap Pen card. You get a card with two empty handwriting faces. Tap one to open the writing screen. It fills the whole screen.\n" +
        "Tools at the top: Pen, Eraser, 4 colours, 3 pen sizes, Undo, Redo, Clear. Tap the tick to keep your writing, the cross to throw it away.\n" +
        "The eraser rubs out just the part you touch.\n" +
        "Pen only: turn it on so your hand resting on the screen does not draw.\n\n" +
        "You can also add handwriting to any normal card with the Handwriting button.",

    "8. Studying: the four buttons" to
        "Open a deck and tap Study. You see the question face. Tap the card to turn it. On the last face you press one of four buttons:\n\n" +
        "AGAIN: I did not know it. The card comes back very soon.\n" +
        "HARD: I knew it, but it was difficult. It comes back after a short time.\n" +
        "GOOD: I knew it. It comes back after a normal time.\n" +
        "EASY: Too easy! It comes back after a long time.\n\n" +
        "Be honest. The app is only as clever as your answers.\n" +
        "Is the app adaptive? Yes. Every card has its own difficulty score. When you press Again or Hard the score goes down and the card comes back sooner. " +
        "When you press Easy it goes up and the card waits longer.",

    "9. Study sets (for example 20 cards at a time)" to
        "A deck can have 100 cards. Studying all of them at once is tiring. So you choose Cards per study set on the deck page: 10, 20, 30, 50 or All.\n\n" +
        "Example: you pick 20. You study 20 cards. Say you press Again on 5 of them.\n" +
        "Those 5 come back at the end of the set, again and again, until you get each one right. " +
        "Only then does the set finish. (You can switch this off in Settings: Repeat missed cards in the same set.)\n" +
        "Then tap Next set to get the next 20.",

    "10. Missed cards that keep coming back" to
        "In Settings you can turn on: Bring missed cards back in later sets.\n\n" +
        "Example with the 25% choice: you finished set 1 and missed 5 cards. Set 2 has 20 cards: 15 brand new cards and 5 older cards you struggled with. " +
        "Those older cards keep joining new sets until you answer them correctly 3 times in a row.\n\n" +
        "You can change the share: 10%, 25% or 40%. Turn the option off and every set has only new or due cards.",

    "11. Correct again and again means less often" to
        "In Settings you can turn on: Reward correct streaks.\n\n" +
        "Example: you answer a card Good three times in a row. The app thinks: you know this well! " +
        "It makes the waiting time longer, for example 10 days becomes 15 days. So the card shows up less often.\n" +
        "If you press Again, the streak goes back to zero.\n" +
        "You can change how many in a row (2 to 5) and how much longer (x1.25, x1.5, x2), or turn it off.",

    "12. Bookmark, Favourite, Suspend: what is the difference?" to
        "BOOKMARK (the little flag, top of the card editor): use it for cards you want to practise quickly. Example: cards for tomorrow's test. " +
        "On the deck page tap Bookmarked to study only those.\n\n" +
        "FAVOURITE (the star): use it for your most important cards. Example: key formulas. Tap Favorites on the deck page to study only those.\n\n" +
        "SUSPEND (three dots in the editor): hides a card from studying without deleting it. Example: a card with a mistake you will fix later. " +
        "Un-suspend it any time. It keeps its history.\n\n" +
        "Other buttons on the deck page: Weak (cards you often get wrong) and Missed today (cards you pressed Again on today).\n" +
        "Note: when you study cards outside their normal time (like Bookmarked), the app counts your right and wrong answers but does not change their schedule.",

    "13. Searching and filters" to
        "Type in the search box. Plain words look inside all faces, formulas, tags and deck names.\n\n" +
        "Special words you can add:\n" +
        "tag:physics - cards with the tag #physics\n" +
        "deck:chemistry - cards in decks with chemistry in the name\n" +
        "favorite - starred cards\n" +
        "bookmarked - bookmarked cards\n" +
        "has:image, has:audio, has:ink - cards with a picture, recording or handwriting\n" +
        "due, overdue - cards waiting for you\n" +
        "state:new, state:learning, state:review - by progress\n" +
        "added:today, added:7d, added:30d - recently made cards\n\n" +
        "Mix them! Example: physics tag:electricity favorite\n" +
        "Under the box there are chips. Tap a chip to switch a filter on or off.",

    "14. Choosing many cards at once" to
        "Long-press (press and hold) a card. It is now selected. Tap other cards to select more, or tap All to select every card in the list.\n" +
        "Then tap the three dots at the top. You can: favourite, bookmark, suspend, add or remove a tag, move to another deck, duplicate, export, or delete.\n" +
        "Example: search electricity, tap All, Add tag, type class-12. All of them get the tag.\n" +
        "Before deleting, the app asks you to confirm.",

    "15. Making cards with AI (the fast way)" to
        "1. Ask any AI: \"Make 20 flashcards about photosynthesis. Give them as a TSV table with 2 columns separated by a Tab character: question, answer. No other text.\"\n" +
        "2. Copy the AI's answer.\n" +
        "3. Open this app and tap Import from Clipboard on the first screen.\n" +
        "4. You see a preview: how many cards were found, and the first few. Pick the deck (or make a new one) and tap Import.\n\n" +
        "More columns make more faces: 3 columns = 3 faces, up to 5. Formulas in the text stay exactly as they are and show as maths.\n" +
        "If some lines are broken, the app imports the good ones and tells you which rows were bad.\n" +
        "Tap Copy TSV template (first screen menu) to copy the column names to give to the AI.",

    "16. Backup: keep your cards safe" to
        "On the first screen tap the three dots, then Export backup. Choose where to save. If Google Drive or OneDrive is on your phone, you can pick it in the list. " +
        "Save a backup before updating the app or changing phones.\n\n" +
        "To get everything back: three dots, Import backup, choose the file. The decks are added as new decks, nothing is deleted.\n" +
        "A backup holds your cards, pictures, recordings, handwriting and progress.",

    "17. Settings" to
        "Open the three dots on the first screen, then Settings. Everything can be switched on or off:\n\n" +
        "Cards per study set - 10, 20, 30, 50 or All.\n" +
        "Repeat missed cards in the same set - missed cards come back until you get them right.\n" +
        "Bring missed cards back in later sets - older missed cards mix into new sets (10%, 25% or 40%).\n" +
        "Reward correct streaks - right several times in a row means longer waiting.\n" +
        "Card turn animation - smooth fade, 3D flip, or none.\n" +
        "Reset all settings - puts everything back as it was.\n" +
        "The theme (light, dark or follow phone) is in the same three-dots menu.",

    "18. If something looks wrong" to
        "A formula shows as plain text: put it between the signs, for example \\(x^2\\), or use the + Formula box.\n" +
        "Paste image says there is no image: copy the screenshot again, or use + Image.\n" +
        "Cannot record: check the microphone is allowed in your phone settings for this app.\n" +
        "A card is missing from Study: it may be suspended, or not due yet. Search for it and check its label.\n" +
        "Before big changes, always do Export backup first."
)

@Composable
fun HelpScreen(back: () -> Unit) {
    var openIdx by rememberSaveable { mutableStateOf(0) }
    Scaffold(topBar = { TopAppBar(title = { Text("Help") }, navigationIcon = { BackIcon(back) }) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 14.dp).verticalScroll(rememberScrollState())) {
            Text(
                "Welcome! Tap a title to open it. Tap it again to close it. Read them in order if you are new.",
                modifier = Modifier.padding(vertical = 8.dp)
            )
            helpSections.forEachIndexed { i, (title, body) ->
                Card(Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { openIdx = if (openIdx == i) -1 else i }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        if (openIdx == i) {
                            Spacer(Modifier.height(8.dp))
                            Text(body, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

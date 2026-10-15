"""Builds the P4 benchmark corpus: twelve site handbooks with the same ten sections and different facts.

    python benchmarks/make_corpus.py          # writes benchmarks/corpus-p4/*.md (deterministic: same files every time)

Why this corpus: the P3 corpus (five documents) is easy enough that every retrieval setting finds the evidence, so a search for a better
configuration has nothing to find. Here every site handbook has a section called "Pump inspection", "Fire drills" and so on, written with
the same vocabulary but with different numbers, names and equipment tags. A question about one site's fire drills is close, by meaning, to the
same section of all eleven other handbooks. Whether the right one comes back depends on the chunking (does the chunk say which site it is?), the
embedding model, keyword matching on tags and names (hybrid search), and how many chunks reach the prompt. That is what AutoTune has to sort out.
"""
import pathlib
import random

SITES = ["Northfield", "Eastport", "Westbrook", "Southgate", "Highmoor", "Lakeside", "Ridgeway", "Fairview", "Stonebridge", "Oakhurst", "Riverside", "Marsh Lane"]
ROLES = ["maintenance lead", "shift supervisor", "site engineer", "safety officer", "operations manager", "duty technician"]
FIRST = ["Ana", "Ben", "Chen", "Dara", "Eli", "Farid", "Gita", "Hugo", "Ines", "Jonas", "Kira", "Luca", "Mira", "Nils", "Omar", "Pia"]
LAST = ["Alder", "Brandt", "Castillo", "Duarte", "Eklund", "Fischer", "Grant", "Hale", "Ivanov", "Jensen", "Keller", "Lindqvist", "Moreau", "Novak"]
ZONES = ["A", "B", "C", "D", "E", "F"]


def person(r):
    return f"{r.choice(FIRST)} {r.choice(LAST)}"


def tag(r, prefix):
    return f"{prefix}-{r.randint(100, 999)}"


def numword(n):
    return str(n)


# Every template is one sentence. {s} is the site, the other fields are drawn per site, so each fact is true of exactly one handbook.
SECTIONS = {
    "Pump inspection": [
        "At {s}, pump {tag} is inspected every {days} days by the {role}.",
        "The inspection of pump {tag} at {s} checks the seal, the bearing temperature and the vibration level, which must stay below {mm} millimetres per second.",
        "If the bearing temperature of pump {tag} rises above {temp} degrees, the {role} at {s} stops the pump and opens a work order.",
        "Spare seals for the pumps at {s} are kept in cabinet {cab} and the stock is counted on the first Monday of each month.",
        "Inspection results at {s} are entered in the maintenance log by {name} and reviewed every quarter.",
        "A pump at {s} that fails two inspections in a row is replaced rather than repaired.",
        "The standby pump {tag2} at {s} is run for {mins} minutes every week to keep its bearings free.",
    ],
    "Fire drills": [
        "{s} holds a full evacuation drill every {months} months, and the fire marshal {name} times how long the building takes to clear.",
        "At {s} the target for clearing the building is {mins} minutes, and a slower drill must be repeated within {days} days.",
        "The assembly point at {s} is the {place}, and staff report to their warden there.",
        "{s} has {n} trained fire wardens, one for every {per} people on site.",
        "Visitors at {s} are told about the alarm test, which takes place every {weekday} at {hour}.",
        "After each drill at {s} the marshal sends a written report to the site director within {days} days.",
        "Smoke doors at {s} are closed every evening by the last person to leave the {zone} wing.",
    ],
    "Lockout and tagout": [
        "Before any repair at {s}, the machine is isolated and locked with a personal padlock, and the key stays with the person doing the work.",
        "At {s} each lockout is recorded on a red tag showing the machine number {tag}, the name of the person and the time.",
        "The energy isolation checklist at {s} has {n} steps, and the {role} signs it before work starts.",
        "A lock at {s} may only be removed by the person who fitted it, or by {name} if that person has left the site and the manager agrees.",
        "At {s} lockout training is repeated every {months} months and takes {hours} hours.",
        "Group lockouts at {s} use a lock box with a limit of {n} padlocks.",
        "Contractors at {s} bring their own padlocks, which are checked at the gate by {name}.",
    ],
    "Visitor access": [
        "Visitors to {s} sign in at the {place} and wear a badge at all times.",
        "At {s} a visitor may not enter the {zone} wing without an escort from the {role}.",
        "Visitor badges at {s} are valid for one day and are collected when the visitor signs out.",
        "{s} limits the number of visitors on site to {n} at once.",
        "Deliveries to {s} must book a slot at least {days} days ahead with {name}.",
        "At {s} the visitor log is kept for {months} months and then destroyed.",
        "Children under {age} are not admitted to {s} except on the open day in {month}.",
    ],
    "Chemical storage": [
        "Chemicals at {s} are stored in cabinet {cab}, which is locked and held to a total of {litres} litres.",
        "At {s} acids and alkalis are kept on separate shelves with at least {cm} centimetres between them.",
        "The safety data sheets for {s} are filed by {name} and a copy is posted next to cabinet {cab}.",
        "A spill kit at {s} is placed beside the door of the chemical store and checked every {days} days.",
        "Flammable liquids at {s} may not exceed {litres} litres outside the cabinet.",
        "At {s} every container is labelled with the date it was opened and is discarded after {months} months.",
        "Staff at {s} who handle chemicals complete a {hours}-hour course before they get a key.",
    ],
    "Shift handover": [
        "At {s} the outgoing {role} briefs the incoming {role} for {mins} minutes at the start of every shift.",
        "The handover form at {s} lists open work orders, equipment out of service and any safety incident of the last {hours} hours.",
        "At {s} the handover is held in the {place} and the form is signed by both people.",
        "Night shifts at {s} start at {hour} and the handover form is checked by {name} the next morning.",
        "If a handover at {s} is missed, the {role} must tell the duty manager within {mins} minutes.",
        "Handover forms at {s} are scanned and kept for {months} months.",
        "At {s} critical alarms are read out loud during the handover, one by one.",
    ],
    "Incident reporting": [
        "At {s} every incident is reported to {name} within {hours} hours, even if nobody was hurt.",
        "Serious injuries at {s} are also reported to the regulator within {days} days.",
        "The incident form at {s} has {n} sections, and photographs are attached when it is safe to take them.",
        "At {s} the review of an incident is led by the {role} and finished within {days} days.",
        "{s} publishes a summary of all incidents on the notice board in the {place} every month.",
        "Near misses at {s} are counted separately and a prize is drawn each quarter among the people who reported them.",
        "At {s} an incident that stops production for more than {hours} hours triggers a full investigation.",
    ],
    "Contractor induction": [
        "Every contractor at {s} completes a {hours}-hour induction before starting work.",
        "The induction at {s} is given by {name} on Tuesdays and Thursdays at {hour}.",
        "Contractors at {s} receive a green helmet sticker valid for {months} months.",
        "At {s} the induction covers the site map, the emergency exits, the lockout rules and the permit system.",
        "A contractor at {s} who breaks a safety rule is removed from site for {days} days.",
        "Contractors at {s} park in the {place} and may not use the staff car park.",
        "At {s} subcontractors must show insurance of at least {amount} thousand euros.",
    ],
    "Waste disposal": [
        "At {s} general waste, paper and metal are collected in separate bins, emptied every {days} days.",
        "Used oil at {s} is stored in the drum area behind the {place} and collected by a licensed carrier every {months} months.",
        "At {s} hazardous waste is logged by {name} on a consignment note before it leaves the site.",
        "The waste team at {s} weighs the skips on the {weekday} of every week.",
        "{s} aims to send less than {pct} percent of its waste to landfill.",
        "At {s} electronic waste is stored in cage {cab} until the monthly collection.",
        "Empty chemical containers at {s} are rinsed three times before they go in the metal skip.",
    ],
    "Emergency contacts": [
        "At {s} the emergency number is displayed in every corridor, and the site director is {name}.",
        "The first aid room at {s} is in the {place}, and there is a trained first aider for every {per} people.",
        "At {s} the nearest hospital is {km} kilometres away and an ambulance is expected within {mins} minutes.",
        "The duty manager at {s} can be reached day and night on extension {ext}.",
        "At {s} the gas supplier's emergency line is posted beside the main valve in the {place}.",
        "Contact details at {s} are checked by {name} every {months} months.",
        "{s} runs an emergency call test every {weekday} at {hour}.",
    ],
}

PLACES = ["main reception", "north car park", "canteen", "loading bay", "east gate", "workshop office", "control room", "training room", "west entrance", "stores building"]
MONTHS = ["March", "May", "June", "September", "October"]
WEEKDAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday"]


def facts(r, site):
    return dict(
        s=site, tag=tag(r, "P"), tag2=tag(r, "SB"), days=r.choice([3, 5, 7, 10, 14, 21, 30]), role=r.choice(ROLES), mm=r.choice([2, 3, 4, 5, 7]),
        temp=r.choice([60, 65, 70, 75, 80, 85]), cab=tag(r, "C"), name=person(r), mins=r.choice([3, 5, 8, 10, 15, 20, 30]), months=r.choice([3, 4, 6, 9, 12, 18, 24]),
        place=r.choice(PLACES), n=r.choice([4, 6, 8, 9, 11, 12, 14]), per=r.choice([15, 20, 25, 30, 40]), weekday=r.choice(WEEKDAYS),
        hour=r.choice(["06:00", "07:30", "09:00", "10:00", "14:00", "22:00"]), zone=r.choice(ZONES), hours=r.choice([2, 3, 4, 6, 8, 12]), age=r.choice([12, 14, 16, 18]),
        month=r.choice(MONTHS), litres=r.choice([50, 100, 150, 200, 250, 400]), cm=r.choice([30, 50, 60, 100]), amount=r.choice([500, 750, 1000, 2000, 5000]),
        pct=r.choice([5, 10, 15, 20, 30]), km=r.choice([4, 7, 12, 18, 25]), ext=r.randint(2000, 9999),
    )


def build(site, index):
    r = random.Random(1000 + index)
    out = [f"# {site} Site Handbook", ""]
    for title, templates in SECTIONS.items():
        f = facts(r, site)
        chosen = r.sample(templates, k=r.choice([4, 5, 5, 6]))
        out += [f"## {title}", "", " ".join(t.format(**f) for t in chosen), ""]
    return "\n".join(out)


def main():
    target = pathlib.Path(__file__).parent / "corpus-p4"
    target.mkdir(exist_ok=True)
    for i, site in enumerate(SITES):
        (target / f"{i + 1:02d}-{site.lower().replace(' ', '-')}.md").write_text(build(site, i), encoding="utf-8", newline="\n")
    words = sum(len(p.read_text(encoding="utf-8").split()) for p in target.glob("*.md"))
    print(f"wrote {len(SITES)} handbooks, {words} words, to {target}")


if __name__ == "__main__":
    main()

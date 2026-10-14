# Build without Maven: JDK 17+.
JAVAC ?= javac
JAVA  ?= java
JAR   ?= jar
SRC    = $(shell find src/main/java -name '*.java')
OUT    = target/classes
JARFILE = target/jdsp.jar

.PHONY: all classes jar test screenshot clean filmation

all: jar

classes:
	mkdir -p $(OUT)
	$(JAVAC) --release 17 -encoding UTF-8 -d $(OUT) $(SRC)

jar: classes
	$(JAR) cfe $(JARFILE) dsp.Main -C $(OUT) .

filmation:
	python3 tools/z80asm.py asm/filmation.asm

test: classes filmation
	$(JAVA) -cp $(OUT) dsp.Tests

screenshot: jar
	$(JAVA) -jar $(JARFILE) --game bagman --screenshot bagman.bmp --frames 300 --mute $(ROM)

screenshot-pirates: jar
	$(JAVA) -jar $(JARFILE) --game pirates --screenshot pirates.bmp --frames 300 --mute $(ROM)

screenshot-filmation: jar filmation
	mkdir -p /tmp/specrom
	python3 -c "from pathlib import Path; p=Path('/tmp/specrom/48.rom'); p.write_bytes(bytes(16384)) if not p.exists() else None"
	$(JAVA) -jar $(JARFILE) --game spectrum48 --screenshot filmation.bmp --frames 150 --mute /tmp/specrom --tape asm/filmation.sna

clean:
	rm -rf target bagman.bmp pirates.bmp filmation.bmp

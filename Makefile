assemble:
	gradle assemble

clean:
	rm -rf .nextflow*
	rm -rf work
	rm -rf build
	gradle clean

test:
	gradle test

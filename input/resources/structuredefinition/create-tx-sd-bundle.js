const fs = require('fs').promises;
const path = require('path');

async function createFHIRTransactionBundle() {
  try {
    // Directory containing StructureDefinition JSON files
    const inputDir = path.join(__dirname);
    
    // Read all files in the directory
    const files = await fs.readdir(inputDir);
    
    // Filter for StructureDefinition files matching the pattern
    const structureDefinitionFiles = files.filter(file => 
      file.endsWith('.json')
    );
    
    // Create the base transaction bundle
    const transactionBundle = {
      resourceType: 'Bundle',
      type: 'transaction',
      entry: []
    };
    
    // Process each StructureDefinition file
    for (const filename of structureDefinitionFiles) {
      // Read the file contents
      const filePath = path.join(inputDir, filename);
      const fileContents = await fs.readFile(filePath, 'utf8');
      
      // Parse the JSON
      const resource = JSON.parse(fileContents);
      
      // Create a transaction bundle entry with PUT method
      const entry = {
        request: {
          method: 'PUT',
          url: `StructureDefinition/${resource.id}`
        },
        resource: resource
      };
      
      // Add the entry to the bundle
      transactionBundle.entry.push(entry);
    }
    
    // Optional: Write the transaction bundle to a file
    const outputPath = path.join(__dirname, 'fhir-transaction-bundle.json');
    await fs.writeFile(outputPath, JSON.stringify(transactionBundle, null, 2), 'utf8');
    
    console.log(`Transaction bundle created with ${transactionBundle.entry.length} entries.`);
    
    return transactionBundle;
  } catch (error) {
    console.error('Error creating FHIR transaction bundle:', error);
    throw error;
  }
}

// Export the function for potential module usage
module.exports = createFHIRTransactionBundle;

// If run directly, execute the function
if (require.main === module) {
  createFHIRTransactionBundle()
    .then(bundle => console.log('Bundle created successfully'))
    .catch(error => console.error('Bundle creation failed', error));
}